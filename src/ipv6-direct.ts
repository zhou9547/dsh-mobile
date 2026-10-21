import { X509Certificate, createPrivateKey } from 'node:crypto'
import { lstat, readFile, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { isIP } from './ip.js'
import { addressAllowed, parseAuthority, parseCidr } from './network.js'
import type { ResolvedGatewayConfig } from './config.js'
import type { MobileAccessControlState, MobileAccessControlStore } from './control.js'
import type { MobileAccessGateway } from './gateway.js'
import { assertManagedParents, ensureManagedDirectory, writeManagedPrivateFile } from './managed-files.js'
import { ensureManagedCa, issueServerCertificate, readManagedCa } from './managed-setup.js'
import { restrictPrivateFile } from './private-file.js'
import type { RemoteProviderController, RemoteProviderStatus } from './remote.js'

const GLOBAL_IPV6 = [parseCidr('2000::/3')]
const SPECIAL_IPV6 = ['2001::/23', '2001:db8::/32', '2002::/16', '3fff::/20'].map(parseCidr)

/** Conservative public unicast policy, shared with Android RemoteHostPolicy. */
export function isPublicIpv6(host: string): boolean {
  return !host.includes('%') && isIP(host) === 6
    && addressAllowed(host, GLOBAL_IPV6) && !addressAllowed(host, SPECIAL_IPV6)
}

export interface Ipv6Settings {
  readonly version: 1
  readonly publicOrigin: string
  readonly listenHost: string
  readonly listenPort: number
}

/** Accept a public IPv6 literal or DNS name; the listener itself is always IPv6. */
export function parseIpv6Settings(value: unknown): Ipv6Settings {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new Error('ipv6_settings_invalid')
  const record = value as Record<string, unknown>
  if (Reflect.ownKeys(record).some(key => !['version', 'publicOrigin', 'listenHost', 'listenPort'].includes(String(key)))
    || record.version !== undefined && record.version !== 1) throw new Error('ipv6_settings_invalid')
  const source = record.publicOrigin
  if (typeof source !== 'string' || source.length > 512 || /[\s\u0000-\u001f\u007f\\@?#]/u.test(source)) throw new Error('ipv6_origin_invalid')
  let url: URL
  try { url = new URL(source) } catch { throw new Error('ipv6_origin_invalid') }
  const host = url.hostname.replace(/^\[|\]$/gu, '')
  const dns = isIP(host) === 0 && host.length <= 253 && host.includes('.')
    && host.split('.').every(label => /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/u.test(label))
    && !['localhost', 'local', 'lan', 'home', 'internal', 'home.arpa'].some(suffix => host === suffix || host.endsWith('.' + suffix))
  if (url.protocol !== 'https:' || url.pathname !== '/' || url.username !== '' || url.password !== ''
    || url.search !== '' || url.hash !== '' || !(isPublicIpv6(host) || dns)) throw new Error('ipv6_origin_invalid')
  const listenHost = record.listenHost ?? '::'
  if (typeof listenHost !== 'string' || listenHost !== '::' && !isPublicIpv6(listenHost)) throw new Error('ipv6_listen_host_invalid')
  const listenPort = record.listenPort ?? Number(url.port || 443)
  if (!Number.isSafeInteger(listenPort) || Number(listenPort) < 1 || Number(listenPort) > 65535
    || listenPort === 3080 || listenPort === 3443 || listenPort === 3444) throw new Error('ipv6_port_invalid')
  // Direct access has no port-mapping layer: advertise precisely the bound port.
  if (Number(url.port || 443) !== listenPort) throw new Error('ipv6_port_mismatch')
  return Object.freeze({ version: 1, publicOrigin: url.origin, listenHost, listenPort: Number(listenPort) })
}

/** Settings and CA remain private to this provider and survive disabling it. */
export class Ipv6ConfigStore {
  readonly settingsFile: string
  private value: Ipv6Settings | undefined
  private errorCode: string | undefined

  constructor(readonly directory: string, readonly ownedRoot: string) {
    this.settingsFile = join(directory, 'settings.json')
  }

  async initialize(): Promise<void> {
    this.value = undefined
    this.errorCode = undefined
    try {
      await assertManagedParents(this.ownedRoot, this.settingsFile, 'ipv6')
      const entry = await lstat(this.settingsFile)
      if (!entry.isFile() || entry.isSymbolicLink() || entry.size > 8192) throw new Error('ipv6_config_invalid')
      await restrictPrivateFile(this.settingsFile)
      this.value = parseIpv6Settings(JSON.parse(await readFile(this.settingsFile, 'utf8')))
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'ENOENT') this.errorCode = 'ipv6_config_invalid'
    }
  }

  settings(): Ipv6Settings | undefined { return this.value }
  status(): Record<string, unknown> {
    return { configured: this.value !== undefined, ...this.value, ...(this.errorCode === undefined ? {} : { errorCode: this.errorCode }) }
  }
  async configure(value: unknown): Promise<void> {
    const settings = parseIpv6Settings(value)
    await writeManagedPrivateFile(this.ownedRoot, this.settingsFile, JSON.stringify(settings) + '\n', 'ipv6')
    this.value = settings
    this.errorCode = undefined
  }
}

export interface Ipv6Certificate {
  readonly caCertFile: string
  readonly certFile: string
  readonly keyFile: string
  readonly fingerprint: string
}

/** Never replace an existing CA implicitly, including after partial file loss. */
export async function ensureIpv6Certificate(store: Ipv6ConfigStore, settings: Ipv6Settings): Promise<Ipv6Certificate> {
  const directory = join(store.directory, 'tls')
  const identityFile = join(store.directory, 'ca-identity.json')
  const tls = {
    mode: 'managed' as const,
    caCertFile: join(directory, 'ca.pem'), caKeyFile: join(directory, 'ca-key.pem'),
    certFile: join(directory, 'server.pem'), keyFile: join(directory, 'server-key.pem'),
  }
  const existing = new Set<string>()
  for (const file of [identityFile, tls.caCertFile, tls.caKeyFile, tls.certFile, tls.keyFile]) {
    await assertManagedParents(store.ownedRoot, file, 'ipv6')
    try {
      const entry = await lstat(file)
      if (!entry.isFile() || entry.isSymbolicLink()) throw new Error('ipv6_certificate_invalid')
      existing.add(file)
    } catch (error) { if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error }
  }
  const hasCa = existing.has(tls.caCertFile) && existing.has(tls.caKeyFile)
  if (!hasCa && existing.size !== 0) throw new Error('ipv6_ca_invalid')
  let expected: string | undefined
  if (existing.has(identityFile)) {
    const marker = JSON.parse(await readFile(identityFile, 'utf8')) as { fingerprint?: unknown }
    if (typeof marker.fingerprint !== 'string' || !/^[a-f0-9]{64}$/u.test(marker.fingerprint)) throw new Error('ipv6_ca_invalid')
    expected = marker.fingerprint
  } else {
    await ensureManagedDirectory(store.ownedRoot, store.directory, 'ipv6')
    await writeFile(identityFile, '{"pending":true}\n', { flag: 'wx', mode: 0o600 })
    await restrictPrivateFile(identityFile)
  }
  await ensureManagedDirectory(store.ownedRoot, directory, 'ipv6')
  let ca: X509Certificate
  try { ca = hasCa ? await readManagedCa(tls) : await ensureManagedCa(tls) }
  catch { throw new Error('ipv6_ca_invalid') }
  const fingerprint = ca.fingerprint256.replaceAll(':', '').toLowerCase()
  if (expected !== undefined && fingerprint !== expected) throw new Error('ipv6_ca_changed')
  if (expected === undefined) await writeManagedPrivateFile(store.ownedRoot, identityFile, JSON.stringify({ fingerprint }) + '\n', 'ipv6')
  const host = new URL(settings.publicOrigin).hostname.replace(/^\[|\]$/gu, '')
  let reusable = false
  try {
    const leaf = new X509Certificate(await readFile(tls.certFile))
    reusable = leaf.verify(ca.publicKey) && leaf.checkPrivateKey(createPrivateKey(await readFile(tls.keyFile)))
      && Date.parse(leaf.validFrom) <= Date.now() && Date.parse(leaf.validTo) > Date.now() + 30 * 86400_000
      && (isIP(host) === 6 ? leaf.checkIP(host) !== undefined : leaf.checkHost(host) !== undefined)
  } catch { /* Repair the leaf under the same CA. */ }
  if (!reusable) await issueServerCertificate(tls, {
    commonName: 'DSH Mobile IPv6', ...(isIP(host) === 6 ? { ipAddresses: [host] } : { dnsNames: [host] }),
  }, tls)
  return { caCertFile: tls.caCertFile, certFile: tls.certFile, keyFile: tls.keyFile, fingerprint }
}

/** Build a separate HTTPS gateway; LAN and other remote origins are not authorized. */
export function ipv6GatewayConfig(template: ResolvedGatewayConfig, settings: Ipv6Settings, certificate: Ipv6Certificate, stateFile: string): ResolvedGatewayConfig {
  return Object.freeze({
    ...template, listenHost: settings.listenHost, listenPort: settings.listenPort,
    authorities: Object.freeze([parseAuthority(new URL(settings.publicOrigin).host)]),
    allowedCidrs: Object.freeze([parseCidr('::/0')]), stateFile,
    instanceId: certificate.fingerprint, pairingCaFile: certificate.caCertFile,
    tls: Object.freeze({ mode: 'provided' as const, certFile: certificate.certFile, keyFile: certificate.keyFile }),
    publicTls: true, discovery: false,
  })
}

/** Serialized provider lifecycle, including same-CA leaf renewal. */
export class Ipv6Controller implements RemoteProviderController {
  private initialized = false
  private disposed = false
  private enabled = false
  private current: MobileAccessGateway | undefined
  private latest: RemoteProviderStatus = { enabled: false, state: 'off' }
  private queue: Promise<void> = Promise.resolve()
  private timer: ReturnType<typeof setInterval> | undefined

  constructor(private readonly options: {
    store: MobileAccessControlStore
    config: Ipv6ConfigStore
    createGateway: (settings: Ipv6Settings) => Promise<MobileAccessGateway>
  }) {}

  status(): RemoteProviderStatus { return this.latest }
  gateway(): MobileAccessGateway | undefined { return this.current }
  private enqueue(operation: () => Promise<void>): Promise<void> {
    const result = this.queue.then(operation, operation)
    this.queue = result.catch(() => {})
    return result
  }
  async initialize(initial?: MobileAccessControlState): Promise<void> {
    await this.enqueue(async () => {
      if (this.disposed) throw new Error('ipv6_controller_unavailable')
      if (this.initialized) return
      this.enabled = (initial ?? await this.options.store.load()).enabled
      this.initialized = true
      if (this.enabled) await this.start()
    })
  }
  async setEnabled(enabled: boolean): Promise<RemoteProviderStatus> {
    await this.enqueue(async () => {
      if (!this.initialized || this.disposed) throw new Error('ipv6_controller_unavailable')
      if (!enabled) await this.stop()
      await this.options.store.save({ version: 1, enabled })
      this.enabled = enabled
      if (enabled && this.current === undefined) await this.start()
      if (!enabled) this.latest = { enabled: false, state: 'off' }
    })
    return this.latest
  }
  async reconnect(): Promise<RemoteProviderStatus> {
    await this.enqueue(async () => {
      if (!this.initialized || this.disposed) throw new Error('ipv6_controller_unavailable')
      await this.stop()
      await this.options.store.save({ version: 1, enabled: true })
      this.enabled = true
      await this.start()
    })
    return this.latest
  }
  async reset(): Promise<RemoteProviderStatus> { return this.setEnabled(false) }
  async close(): Promise<void> {
    this.disposed = true
    await this.enqueue(async () => { await this.stop(); this.latest = { enabled: this.enabled, state: 'off' } })
  }
  private async start(): Promise<void> {
    if (this.disposed) return
    const settings = this.options.config.settings()
    if (settings === undefined) {
      this.latest = { enabled: true, state: 'unavailable', errorCode: 'ipv6_config_missing' }
      return
    }
    this.latest = { enabled: true, state: 'starting', origin: settings.publicOrigin }
    try {
      this.current = await this.options.createGateway(settings)
      if (this.disposed) { await this.stop(); return }
      this.latest = { enabled: true, state: 'ready', origin: settings.publicOrigin }
      this.timer = setInterval(() => {
        void this.enqueue(async () => {
          if (this.current === undefined || this.disposed) return
          try {
            await ensureIpv6Certificate(this.options.config, settings)
            await this.current.refreshProvidedTls()
          } catch {
            await this.stop()
            this.latest = { enabled: this.enabled, state: 'error', errorCode: 'ipv6_certificate_invalid' }
          }
        }).catch(() => {})
      }, 3600_000)
      this.timer.unref()
    } catch (error) {
      const code = (error as NodeJS.ErrnoException).code
      const message = error instanceof Error ? error.message : ''
      this.latest = { enabled: true, state: 'error', origin: settings.publicOrigin,
        errorCode: code === 'EADDRINUSE' ? 'ipv6_port_in_use' : code === 'EADDRNOTAVAIL' ? 'ipv6_address_unavailable'
          : ['ipv6_ca_invalid', 'ipv6_ca_changed'].includes(message) ? message : 'ipv6_start_failed' }
    }
  }
  private async stop(): Promise<void> {
    clearInterval(this.timer)
    this.timer = undefined
    if (this.current !== undefined) { await this.current.close(); this.current = undefined }
    this.latest = { enabled: this.enabled, state: 'off' }
  }
}
