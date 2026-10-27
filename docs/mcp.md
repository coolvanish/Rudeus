# MCP servers for this project

Configured in `.mcp.json` (project scope; Claude Code asks once before enabling each):

| Server | Why | Notes |
|---|---|---|
| `context7` | Current docs for SceneView 4.x, ARCore, Ktor 3, kotlinx, Compose Multiplatform. The AR API changed a lot between 2.x and 4.x; never write SceneView code from memory. | Library ids: `/sceneview/sceneview`, `/websites/developers_google_ar`, `/google-ar/arcore-android-sdk`, `/websites/sceneview_github_io_api_sceneview-android`. Also enabled globally. |
| `mobile` (`@mobilenext/mobile-mcp`) | Drive the Pixel over adb from an agent: launch the app, tap, screenshot, read the screen. Useful for the Compose screens (Capture/Metrics) and for grabbing evidence screenshots. Not for AR (the camera feed needs the real table). | Needs `adb devices` to list the phone. Pinned to 1.0.14; bump deliberately. |

Already available in the desktop app and useful here: **Claude Docs** (the editable build plan lives there) and
**Google Drive** (style reference images, the Day 7 video draft). **Figma** needs `/mcp` authorisation if you want
to mock the HUD there; `DESIGN.md` is the source of truth either way.

Not added on purpose: a GitHub MCP (`gh` CLI is logged in and enough), a Gradle MCP (Gradle is a shell command),
and the "sceneview" MCP mentioned in the plan (no such server exists; Context7 covers it).
