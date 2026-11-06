# Vyron X light sign-in page

## What changed
- The sign-in page is now always light. The rest of the app keeps the user's chosen theme.
- The page borrows the user's saved theme accent, darkened about 40% so text and buttons stay readable on white. Hue is preserved, so the brand color stays recognizable.
- New internal palette `auth_light` in `ui/UiSupport.kt`. It is hidden from the theme picker in Settings.
- Background: soft wash of the accent fading to white.
- Status bar icons are dark. The dark overlay on the sign-in page is removed.
- The Google button is unchanged (white with a gray border).

## Not changed yet
- Email and phone sign-in buttons are not on this page. The server's email and phone endpoints do not yet return the `appInstallation` identity that the app requires. Fix the server first, then add the buttons.
- Other screens still follow the user's theme. Light versions of those are a separate pass.

## Verification
- Bracket balance checked. NOT compiled. Build the app and check the sign-in page in at least two themes (for example Netrunner and Light Gold) before release.
