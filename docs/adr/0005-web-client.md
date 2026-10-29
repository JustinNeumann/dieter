# ADR 0005: A web client inside a trusted host, and extension points

- Status: accepted in the fork (JustinNeumann/dieter), 2026-10
- Reference implementation and upstream proposal:
  [dieter-addon-kotlin](https://github.com/JustinNeumann/dieter-addon-kotlin)
  (`docs/proposals/web-client.md`)

## Context

The Home Assistant add-on runs a Dieter daemon inside Home Assistant. People
reach it through Home Assistant's frontend (Ingress, Nabu Casa, the Companion
app), where a native app can't be installed. Until now the add-on had its own
JavaScript page that drove the local daemon through the CLI and repeated rules
the Kotlin core already has. Two invariants stood in the way of reusing the
shared Compose UI there: "Dieter has no web UI", and "the native clients are
presentation-only clients of the shared Kotlin core". The add-on also has
features upstream's apps lack: agent questions, enrolling its own daemon,
Claude sign-in, its SSH key, work-folder retention, repositories under
`/data/projects` and Dreamfall.

## Decision

- **The core runs in a host, not in the browser.** The shared Compose UI
  (`apps/kotlin/ui`) is compiled for the browser (Kotlin/Wasm). A web host
  (JVM) runs `ClientApi` with the JVM platform as one roster device. The page
  is a remote presentation of that core over one WebSocket: `dispatch` and
  `observe` of `dieter.client.v1`, the byte bridge the iOS app uses. The
  browser holds no device key, session or roster.
- **Served only by a trusted host.** The page is served by a host the person
  already authenticates with (the add-on behind Home Assistant Ingress), never
  by the gateway. The gateway root stays 404.
- **Rules stay in a core.** The web build adds only platform hooks: clipboard,
  files, links, theme, and HTML islands for reading mail and Markdown.
- **Sign-in return address per attempt.** The page lives at an address only
  the page knows (the Home Assistant URL it was opened through). A host sets
  `RuntimeConfig.oauthRedirectPerAttempt`, and `BeginSignIn.redirect_uri`
  then names the return address of that attempt. The core checks it: http or
  https, a host, no query or fragment. It keeps the address in
  `PendingSignIn.redirect_uri` and completes only a callback to it. The
  gateway still accepts only addresses on its allow-list. Apps keep their
  fixed URL scheme; the flag is off by default.
- **Deep links go through the core.** `OpenConversationRequest` asks the core
  what a link to a card should open. It answers with a `NotificationTarget`:
  the conversation, or the Inbox when the card is unknown or archived. That is
  the same resolution as a notification tap (`OpenRequests`). The UI only
  navigates.
- **Extension points.** `MobileExtensions` (`apps/kotlin/ui/…/extensions`)
  lets a host add:
  - Tools pages;
  - sections on built-in tool pages;
  - Inbox sections;
  - conversation widgets;
  - Projects menu actions;
  - a board renderer.

  Extensions are presentation like the screens around them. They keep drafts
  and call a core. The default is empty, so the native apps don't change.
- **A host's own features have their own core.** The add-on core
  (dieter-addon-kotlin `addon/`) runs in the web host beside Dieter's core. It
  has its own protocol (`dieter.addon.v1`): commands, plus slices for the
  gateway, SSH, Claude, settings and questions. It reaches the add-on's bridge
  on loopback. Its pure rules (`AddonRules`: addresses, repositories, answer
  readiness) also run in the page, the way `Labels.validate` does, so buttons
  know when they are ready.

## Consequences

- `docs/invariants.md` changes two rules:
  - The gateway serves no UI, and a web client exists only inside a trusted
    host.
  - Every client is presentation-only: the web client and host extensions as
    well as the native apps.
- The client protocol gains `BeginSignIn.redirect_uri`,
  `PendingSignIn.redirect_uri` and `OpenConversationRequest`. All three are
  additive.
- A web host is one more roster device. The person approves it once with its
  pairing code, and removing it under Devices cuts off every browser that
  uses it.
- Accessibility on the web is whatever Compose provides. In Compose
  Multiplatform 1.12, the browser's accessibility tree keeps the last popup's
  nodes after the popup closes. Screen readers then miss the page until the
  next popup opens. Compose 1.13 fixes this; it reaches the web client with
  the fork's next Compose update.
- The add-on core lives outside the fork. The extension points are the only
  UI change the add-on needs here.
