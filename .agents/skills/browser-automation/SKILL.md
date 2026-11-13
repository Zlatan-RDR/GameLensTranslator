---
name: browser-automation
description: Drive the user's visible in-app browser tab live in synced mode, or automate headless Chromium via Puppeteer to browse websites, fill forms, click buttons, take screenshots, inspect elements, and automate web tasks.
---

# Browser Automation Skill

Two modes. Prefer SYNCED mode whenever the user asks you to act on a page they can see.

## Synced mode: drive the visible browser tab

`pocket-browser attach` connects your commands to the browser tab the user is looking at inside the app. Every navigate, click, and keystroke appears on their screen in real time.

- `pocket-browser attach`: attach to the visible browser tab. The user watches everything live and can take over instantly by tapping the tab or the Take over button.
- Once attached, `open`, `click`, `type`, `press`, `screenshot`, `get-text`, `eval`, and `status` all drive the visible tab.
- `pocket-browser detach`: release the tab when you are done. The user gets full control back immediately.
- Rules: attach ONLY when the user explicitly asked you in chat to do something in the browser. Never attach silently, speculatively, or on your own initiative. When you attach, tell the user in chat that you are now driving their visible tab and they can take over at any moment. You cannot open, close, or switch the user's tabs. Every action is logged to the session transcript automatically.
- If `attach` fails, tell the user to open the page in the app's browser tab first, then try again.

## Headless mode: background automation

For work the user does not need to watch:

- `pocket-browser start`: Start headless Chromium daemon on port 9222.
- `pocket-browser stop`: Terminate Chromium (also detaches from the browser tab).
- `pocket-browser status`: Check active URL, title, tab count, mode, and connection state.
- `pocket-browser tabs`: List open tabs. `new-tab [url]`, `switch-tab <index>`, `close-tab <index>`: manage headless tabs.

## Shared commands

- `pocket-browser open <url>`: Navigate to any website (e.g. `pocket-browser open "https://github.com"`).
- `pocket-browser click <selector>`: Click an element by CSS selector.
- `pocket-browser type <selector> <text>`: Type text into an input field or textarea.
- `pocket-browser press <key>`: Press a key like `Enter`, `Tab`, `Escape`.
- `pocket-browser screenshot [path]`: Capture a screenshot of the active page.
- `pocket-browser get-text [selector]`: Extract inner text from elements for AI analysis.
- `pocket-browser eval "<code>"`: Evaluate JavaScript expression in the page context.
