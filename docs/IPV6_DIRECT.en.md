# IPv6 direct HTTPS

[中文指南](IPV6_DIRECT.md)

This fork adds **IPv6 direct HTTPS** as an independent remote transport. The computer runs a dedicated Mobile HTTPS gateway using a public IPv6 literal or a custom domain, without FRP or an external reverse proxy.

## Network preparation

1. The computer needs an inbound-reachable public IPv6 address. The phone's network also needs IPv6 routing.
2. For a custom domain, set its DNS AAAA record to this computer's public IPv6. Use direct DNS, without a TLS-terminating CDN/proxy. Do not add an A record pointing at another service.
3. Allow only the selected TCP port, for example `3445`, in the router's IPv6 inbound policy and the computer firewall. The plugin does not change DNS or firewall rules automatically.

## Start and pair

1. Open desktop DSH **Mobile access → Remote access → Self-hosted connection** and select **IPv6 direct HTTPS**.
2. Enter the complete HTTPS origin, such as `https://phone.example.com:3445` or `https://[your-public-IPv6]:3445`. The certificate covers that host, and the advertised port must equal the local listening port. HTTPS port 443 is supported but may require low-port binding privileges on Linux. Reserved DSH/LAN/backend ports `3080`, `3443` and `3444` are rejected.
3. Keep the IPv6 listener at `::` for IPv6 interfaces only, or enter one explicit local public IPv6 address. Do not enter a domain in this field.
4. Click **Generate certificate and start HTTPS**. The plugin creates a private CA and server certificate, keeping keys in its restricted private state.
5. Generate the pairing QR or copy the pairing link below.
6. In this fork's **Android 0.6.6 candidate**, select **Remote access**, then scan the QR or paste the complete pairing link.

The App fetches `/mobile-access/ca.cer` and verifies its SHA-256 fingerprint against the `dsh2` pairing key in the QR/link. It saves the matching CA privately before sending the one-time pairing token over HTTPS. **No Android system CA installation is required.** A bare address does not authorize a device; the QR or full pairing link is still required.

## Certificates and address changes

- Leaves are checked hourly and renewed under the same CA near expiry. Same-CA leaf renewal does not require pairing again.
- When the domain stays the same but the public IPv6 changes, update AAAA. A `::` listener and domain certificate keep working without replacement. An explicitly bound IPv6 must also be updated and saved.
- A changed domain/Origin requires new pairing information. The replacement domain leaf is signed by the existing CA.
- A missing, expired or replaced CA stops access rather than silently replacing trust. Verify and back up private state before deliberately rebuilding identity and pairing again.
- **Ready** means the local HTTPS listener started. It does not verify public DNS, router policy or the phone's IPv6 connectivity. Test from another IPv6 network; local tests are not public-route acceptance.

## Actions build

Pushing `codex/ipv6-direct-https` runs [IPv6 HTTPS APK](../.github/workflows/ipv6-apk.yml). Successful runs provide `dsh-mobile-ipv6-apk` and `dsh-mobile-ipv6-plugin` artifacts. The APK includes a SHA-256 file. It is a Debug-signed test build and cannot replace an upstream release signed with a different key. Preserve the existing App and pairing data instead of clearing data to bypass a signature mismatch.
