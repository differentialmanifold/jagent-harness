# Research the web with a browser

[Documentation](../README.md) · [简体中文](browser-research.zh-CN.md)

Ask mainbot a question that needs current sources. It sends the investigation to a Project, whose Coding Runner searches and reads pages in a real browser. The answer comes back to the original web or Feishu conversation with links. This uses the Runner's network access and requires no search API key.

## Try it in a Coding Project

Projects on a Coding Runner include the **browser-research** and **playwright-cli** Skills automatically when **bash** is enabled. You can inspect both under **Project → Configuration → Skills**. The model loads their instructions when a question needs a browser.

> Look up the most recent stable SQLite release. Verify its date on the official website and link the release notes.

Then ask in the same chat:

> What changed in the previous release? Show me which page supports your answer.

The Project chooses the search terms and sources. It can start at a known documentation site, or use a search website that permits automated queries. To prefer a particular search page, put its URL in the Project instructions. Search snippets help select sources. The answer should cite the pages the Project actually read and identify anything it could not verify.

Each Project chat gets a distinct Playwright session on the Runner, including chats sharing a workspace. The Harness supplies the session name and Skill instructions. Updating these Skills only requires deploying the Harness. The browser starts on demand. If a site requires interactive verification, the Project can use another source or report the gap.

## Runner requirements

The Runner machine needs Node.js 20+ with npm, or a working Playwright CLI on its shell PATH. On first use the Project can run the pinned CLI through npx, use installed Google Chrome, or download Playwright Chromium. Later calls reuse the local installation. For an offline Runner, prepare the CLI and browser under the Runner account before disconnecting.

On headless Linux, Chromium works without a desktop. If system libraries are missing, the machine administrator can install them with `npx --yes --prefer-offline --package=@playwright/cli@0.1.25 playwright-cli install-browser --with-deps chromium`.
