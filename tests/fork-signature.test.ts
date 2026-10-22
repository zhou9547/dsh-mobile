import { spawnSync } from 'node:child_process'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const expected = 'd2e0a4b8a1c071a23b5612099441fa78ada84ea3aadf93c4063957a7093083f0'
function verify(report: string) {
  return spawnSync(process.execPath, [resolve('scripts/verify-fork-signature.mjs')], { input: report, encoding: 'utf8' })
}
describe('persistent fork signing gate', () => {
  it('accepts the verified single fork signer', () => {
    expect(verify(`Verifies\nNumber of signers: 1\nSigner #1 certificate SHA-256 digest: ${expected}\n`).status).toBe(0)
  })
  it('rejects another signer, including a temporary Debug key', () => {
    expect(verify(`Verifies\nNumber of signers: 1\nSigner #1 certificate SHA-256 digest: ${'a'.repeat(64)}\n`).status).toBe(1)
  })
  it('rejects missing verification, missing certificates and multiple signers', () => {
    for (const report of [
      `Number of signers: 1\nSigner #1 certificate SHA-256 digest: ${expected}\n`,
      'Verifies\nNumber of signers: 1\n',
      `Verifies\nNumber of signers: 2\nSigner #1 certificate SHA-256 digest: ${expected}\n`,
    ]) expect(verify(report).status).toBe(1)
  })
})
