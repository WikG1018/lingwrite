<p align="center"><a href="PRIVACY.md">简体中文</a> · <b>English</b> · <a href="PRIVACY.zh-TW.md">繁體中文</a> · <a href="PRIVACY.ja.md">日本語</a></p>

# Privacy

LingWrite has no server and no account system. We don't collect or upload your data, and we have no way to see it.

## Where your data lives

- Books, chapters, lore, outlines, foreshadowing, notes, stats and backup files are stored only on your phone, either in the app's private storage or in a location you choose.
- API keys are encrypted with the Android Keystore. They are **never written to backup files** and never leave the device, except when they are used to call the provider you chose.

## When the app goes online

| What you're doing | Who receives it | What is sent |
|---|---|---|
| Using AI features | The provider you set up under Model settings | The manuscript excerpts, lore and outline the task needs, plus your API key |
| Optional semantic search (off by default) | The embeddings endpoint you set up | Chunks of your manuscript |
| Checking for updates | GitHub (`api.github.com`, `github.com`) or the mirror you chose | A plain HTTP request with no book data |
| Sending feedback | The GitHub issue page | Only what you type and confirm, plus device details that you can see before sending |

How each provider handles the data you send them is governed by their own privacy policy. Please pick providers you trust.

## What we don't do

- No analytics, tracking or crash reporting.
- No ad SDKs.
- No access to contacts, location or clipboard history.

## Notifications

Notifications are used only for finished background tasks, foreshadowing reminders and new versions. You can turn each category off in Settings.

## Contact

For privacy questions, open a GitHub issue. Report security vulnerabilities privately as described in [SECURITY.en.md](SECURITY.en.md).
