# Connect an Agent to Feishu

[Documentation](../README.md) · [Personal Agents](agents.md)

Feishu is an optional channel for the same Agent you use in the Console. Create and try an Agent first; no channel is needed for planning, project execution or memory.

## Connect

1. Select the Agent and open **Configuration → Channels → Connect with Feishu**.
2. Scan the code or open the authorization page, then confirm the app and permissions. Your organization may require administrator approval.
3. Wait for **CONNECTED**, then send a text direct message to the bot. The authorizing account becomes its owner.

**Disconnect** pauses messaging and retains credentials, the paired account and conversation. **Reconnect** resumes the saved connection without another QR authorization. Disconnected accounts stay disconnected after a server restart. Use **Use another app** to replace the bot.

### Use an existing app

Expand **Use an existing app** and enter its App ID and App Secret. Enable bot capability and long-connection delivery for `im.message.receive_v1`. Grant `im:message:send_as_bot` and `im:message.p2p_msg:readonly` for messaging, and `im:message.reactions:write_only` for the processing indicator. Publish the app, then send the Console's pairing code to the bot. Other senders and group chats are ignored.

No public webhook URL is required. The server uses the official Java SDK's outbound WebSocket connection. The [server host settings](../../jagent-harness-sdk/README.md#personal-agent-settings) describe the optional dependency.

## Messages and background work

When the Agent accepts your message, a typing reaction appears beneath it in Feishu. It stays while the message is queued or being processed and clears when that turn finishes. If replies arrive without the indicator, check **Configuration → Channels**. For a previously connected bot, grant the reaction permission and publish the updated Feishu app.

Feishu messages and final replies appear in the same conversation on the web. Detailed tool calls stay in the web Console. Text and rich-text direct messages are supported; images and files are not passed to the model.

Project results and scheduled checks wake the Agent automatically. It reports meaningful progress, results, failures or questions to the originating conversation and Feishu channel. A check with nothing to report can stay quiet. Work started on the web stays on the web, even if the Agent has Feishu connected.

If delivery fails, the reply remains visible in the Console. Fix the connection and use **Retry delivery**. A retry after an uncertain network outcome may deliver a message twice.

Credentials are stored separately from Agent instructions and memory and are not included in model input. Protect the server database as application credentials.
