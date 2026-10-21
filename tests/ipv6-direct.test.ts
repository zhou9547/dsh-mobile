import { X509Certificate } from 'node:crypto'
import { mkdtemp, readFile, rm, unlink, writeFile } from 'node:fs/promises'
import { createServer, request as httpRequest } from 'node:http'
import { request as httpsRequest } from 'node:https'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import type { AddressInfo } from 'node:net'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { parseGatewayConfig } from '../src/config.js'
import { MemoryDeviceStore } from '../src/storage.js'
import { MobileAccessGateway } from '../src/gateway.js'
import { Ipv6ConfigStore, Ipv6Controller, ensureIpv6Certificate, ipv6GatewayConfig, isPublicIpv6, parseIpv6Settings } from '../src/ipv6-direct.js'
import { parseCidr } from '../src/network.js'

const cleanups: Array<() => Promise<void>> = []
afterEach(async () => { for (const cleanup of cleanups.splice(0).reverse()) await cleanup() })
async function fixture() {
  const root = await mkdtemp(join(tmpdir(), 'dsh-ipv6-'))
  cleanups.push(() => rm(root, { recursive: true, force: true }))
  const config = new Ipv6ConfigStore(join(root, 'ipv6'), root)
  await config.initialize()
  return { root, config }
}
const settings = () => parseIpv6Settings({ publicOrigin: 'https://phone.example.com:3445' })

describe('IPv6 direct HTTPS', () => {
  it('accepts HTTPS domains, literal IPv6 and exact custom ports', () => {
    expect(settings()).toMatchObject({ publicOrigin: 'https://phone.example.com:3445', listenHost: '::', listenPort: 3445 })
    expect(parseIpv6Settings({ publicOrigin: 'https://[2606:4700::1111]:8443' }).listenPort).toBe(8443)
    expect(parseIpv6Settings({ publicOrigin: 'https://phone.example.com' }).listenPort).toBe(443)
  })
  it.each(['http://phone.example.com:3445', 'https://user:pass@phone.example.com:3445',
    'https://phone.example.com:3445/path', 'https://phone.example.com:3445?x=1',
    'https://phone.local:3445', 'https://192.168.1.1:3445', 'https://[::1]:3445',
    'https://[fe80::1]:3445', 'https://[2001:db8::1]:3445'])('rejects unsafe direct origin %s', publicOrigin => {
    expect(() => parseIpv6Settings({ publicOrigin })).toThrow('ipv6_origin_invalid')
  })
  it('rejects IPv4 listeners, reserved ports and a different advertised port', () => {
    expect(() => parseIpv6Settings({ ...settings(), listenHost: '0.0.0.0' })).toThrow('ipv6_listen_host_invalid')
    expect(() => parseIpv6Settings({ ...settings(), listenPort: 8443 })).toThrow('ipv6_port_mismatch')
    expect(() => parseIpv6Settings({ publicOrigin: 'https://phone.example.com:3443' })).toThrow('ipv6_port_invalid')
  })
  it('uses the same IPv6 host vectors as Android', async () => {
    const cases = JSON.parse(await readFile(new URL('../apps/mobile/contract/url-policy-cases.json', import.meta.url), 'utf8')).remoteHost
    for (const row of cases.filter((row: { host: string }) => row.host.includes(':'))) {
      expect(isPublicIpv6(row.host), row.name).toBe(row.allowedRemote)
    }
  })
  it('persists configuration and signs domain and IP leaves under one stable CA', async () => {
    const { config, root } = await fixture()
    await config.configure(settings())
    const first = await ensureIpv6Certificate(config, settings())
    const leaf = new X509Certificate(await readFile(first.certFile))
    expect(leaf.checkHost('phone.example.com')).toBe('phone.example.com')
    expect(leaf.ca).toBe(false)
    const ca = new X509Certificate(await readFile(first.caCertFile))
    expect(leaf.verify(ca.publicKey)).toBe(true)
    const again = await ensureIpv6Certificate(config, settings())
    expect(new X509Certificate(await readFile(again.certFile)).fingerprint256).toBe(leaf.fingerprint256)
    const ip = parseIpv6Settings({ publicOrigin: 'https://[2606:4700::1111]:8443' })
    const changed = await ensureIpv6Certificate(config, ip)
    expect(changed.fingerprint).toBe(first.fingerprint)
    expect(new X509Certificate(await readFile(changed.certFile)).checkIP('2606:4700::1111')).toBeDefined()
    const loaded = new Ipv6ConfigStore(join(root, 'ipv6'), root)
    await loaded.initialize()
    expect(loaded.settings()).toEqual(settings())
  })
  it('refuses a missing CA key or replaced CA identity instead of trusting a new one', async () => {
    const { config } = await fixture()
    const original = await ensureIpv6Certificate(config, settings())
    await writeFile(join(config.directory, 'ca-identity.json'), JSON.stringify({ fingerprint: 'a'.repeat(64) }))
    await expect(ensureIpv6Certificate(config, settings())).rejects.toThrow('ipv6_ca_changed')
    await writeFile(join(config.directory, 'ca-identity.json'), JSON.stringify({ fingerprint: original.fingerprint }))
    await unlink(join(config.directory, 'tls', 'ca-key.pem'))
    await expect(ensureIpv6Certificate(config, settings())).rejects.toThrow('ipv6_ca_invalid')
  })
  it('does not start an inactive provider or recreate a listener after disposal', async () => {
    const { config } = await fixture()
    const save = vi.fn(async () => {})
    const createGateway = vi.fn(async () => { throw new Error('unexpected startup') })
    const controller = new Ipv6Controller({ config, store: { load: async () => ({ version: 1, enabled: false }), save }, createGateway })
    await controller.initialize({ version: 1, enabled: false })
    expect(createGateway).not.toHaveBeenCalled()
    await controller.close()
    await expect(controller.reconnect()).rejects.toThrow('ipv6_controller_unavailable')
    expect(save).not.toHaveBeenCalled()
  })
  it('requires pairing over real IPv6 TLS and emits a fingerprint-bound dsh2 QR/link', async () => {
    const { root, config } = await fixture()
    const certificate = await ensureIpv6Certificate(config, settings())
    const template = parseGatewayConfig({ listenHost: '127.0.0.1', listenPort: 0, publicAuthorities: ['127.0.0.1'],
      allowedCidrs: ['127.0.0.0/8'], upstreamOrigin: 'http://127.0.0.1:3080',
      stateFile: join(root, 'lan-devices.json'), tls: { mode: 'disabled' } })
    const direct = ipv6GatewayConfig(template, settings(), certificate, join(root, 'remote-devices.json'))
    expect(direct).toMatchObject({ publicTls: true, discovery: false, pairingCaFile: certificate.caCertFile })
    // Loopback-only fixture: retain the public Host/SNI, allocate the actual IPv6 port.
    const gateway = new MobileAccessGateway({ ...direct, listenHost: '::1', listenPort: 0, allowedCidrs: [parseCidr('::1/128')] }, new MemoryDeviceStore())
    cleanups.push(() => gateway.close())
    await gateway.start()
    const ca = await readFile(certificate.caCertFile, 'utf8')
    const request = (path: string, body?: object, trust: string | undefined = ca, host = 'phone.example.com:3445') => new Promise<{ status: number; body: string }>((resolve, reject) => {
      const outgoing = httpsRequest({ host: '::1', port: gateway.address().port, servername: 'phone.example.com',
        ca: trust, agent: false, path, method: body ? 'POST' : 'GET', headers: {
          host, origin: 'https://phone.example.com:3445', 'sec-fetch-site': 'same-origin', 'content-type': 'application/json',
        } }, response => {
        const chunks: Buffer[] = []
        response.on('data', chunk => chunks.push(Buffer.from(chunk)))
        response.once('end', () => resolve({ status: response.statusCode ?? 0, body: Buffer.concat(chunks).toString() }))
      })
      outgoing.setTimeout(3000, () => outgoing.destroy(new Error('fixture timeout')))
      outgoing.once('error', reject)
      outgoing.end(body ? JSON.stringify(body) : undefined)
    })
    await expect(request('/mobile-access/discovery', undefined, '')).rejects.toThrow()
    expect((await request('/mobile-access/ca.cer')).status).toBe(200)
    expect((await request('/mobile-access/discovery', undefined, ca, 'foreign.example.com:3445')).status).toBe(403)
    expect((await request('/mobile-access/auth/native-pair', { token: 'x'.repeat(43) })).status).toBe(401)
    const adminRoute = gateway.localAdminRoute()
    const admin = createServer((req, res) => { void adminRoute.handler(req, res) })
    await new Promise<void>(resolve => admin.listen(0, '127.0.0.1', resolve))
    cleanups.push(() => new Promise<void>(resolve => admin.close(() => resolve())))
    const port = (admin.address() as AddressInfo).port
    const opened = await new Promise<{ appKey: string; appPairUrl: string; token: string; qrSvg: string }>((resolve, reject) => {
      const outgoing = httpRequest({ host: '127.0.0.1', port, path: '/api/mobile-access/pairing/open', method: 'POST', agent: false,
        headers: { origin: `http://127.0.0.1:${port}`, 'sec-fetch-site': 'same-origin', 'content-type': 'application/json' } }, response => {
        const chunks: Buffer[] = []
        response.on('data', chunk => chunks.push(Buffer.from(chunk)))
        response.once('end', () => resolve(JSON.parse(Buffer.concat(chunks).toString())))
      })
      outgoing.once('error', reject); outgoing.end('{}')
    })
    expect(opened.appKey).toBe(`dsh2.${certificate.fingerprint}.${opened.token}`)
    expect(opened.appPairUrl).toBe(`https://phone.example.com:3445/mobile-access/pair#key=${opened.appKey}`)
    expect(opened.qrSvg).toContain('<svg')
    const paired = await request('/mobile-access/auth/native-pair', { token: opened.token, label: 'IPv6 fixture' })
    expect(paired.status).toBe(201)
    const credential = JSON.parse(paired.body)
    expect(credential.instanceId).toBe(certificate.fingerprint)
    expect((await request('/mobile-access/auth/native-pair', { token: opened.token })).status).toBe(401)
    expect((await request('/mobile-access/auth/native-renew', { deviceToken: credential.deviceToken })).status).toBe(200)
  })
})
