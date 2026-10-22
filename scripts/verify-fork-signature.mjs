import { readFileSync } from 'node:fs'

// Fork artifacts have their own persistent key. The upstream release verifier stays unchanged.
const expected = 'd2e0a4b8a1c071a23b5612099441fa78ada84ea3aadf93c4063957a7093083f0'
const lines = readFileSync(0, 'utf8').split(/\r?\n/u).map(line => line.trim())
const counts = lines.filter(line => line.startsWith('Number of signers:'))
const certificates = lines.filter(line => line.includes('certificate SHA-256 digest:'))
const pattern = /^(?:Signer #1|V[234](?:\.1)? Signer:) certificate SHA-256 digest: ([a-f\d]{64})$/iu
if (!lines.includes('Verifies') || counts.length !== 1 || counts[0] !== 'Number of signers: 1'
  || certificates.length === 0 || certificates.some(line => pattern.exec(line)?.[1]?.toLowerCase() !== expected)) {
  console.error('Fork APK must have exactly one signer with the persistent DSH Mobile fork certificate.')
  process.exitCode = 1
} else {
  console.log('Persistent DSH Mobile fork signing certificate verified.')
}
