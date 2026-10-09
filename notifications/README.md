# TV problem-report relay

Deploy this directory as a dedicated personal Vercel project. Configure `RESEND_API_KEY` (sending-only), `REPORT_SENDER` (verified sender), and `REPORT_RECIPIENT` as server-side secrets. No fallback recipient exists. Run `npm test`.

Before enabling reporting in an APK, configure a Vercel Firewall rate-limit rule for `POST /api/report`: deny requests beyond 5 per IP per hour. Use a deny response, not a browser challenge, because this is a native TV app. Resend idempotency keys suppress identical payloads within a UTC day. Set spend alerts and verify the rule returns 429 before publishing the endpoint in releases. This public endpoint is deliberately not authenticated by an extractable APK secret; it accepts only a fixed, small diagnostic schema and can send only to the configured recipient.

Set GitHub repository variable `TV_REPORT_URL` to the deployed HTTPS `/api/report` URL after testing with the configured inbox. Empty URL leaves reporting visibly unconfigured. Do not publish a build claiming working notifications before a delivery test succeeds.

Deployment test: submit one valid structural report, verify the email arrives, repeat the same report and verify no second email, then test malformed payloads, denied rate-limit requests, and a simulated Resend failure.
