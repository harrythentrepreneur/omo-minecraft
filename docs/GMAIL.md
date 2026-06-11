# Gmail setup

The Gmail tools (`gmail_list`, `gmail_read`, `gmail_draft`, `gmail_send`) use
your own OAuth client + refresh token. One-time setup:

## 1. Create an OAuth client

1. Go to https://console.cloud.google.com → create a project.
2. APIs & Services → Library → enable **Gmail API**.
3. APIs & Services → OAuth consent screen → external → fill required fields.
   Add yourself as a Test user.
4. Credentials → Create credentials → OAuth client ID → "Desktop app".
5. Note the `client_id` and `client_secret`.

## 2. Get a refresh token

Easiest path: use Google's OAuth playground.

1. Visit https://developers.google.com/oauthplayground
2. ⚙ (top right) → "Use your own OAuth credentials" → paste your client id/secret.
3. In step 1, paste the scope:
   ```
   https://www.googleapis.com/auth/gmail.modify
   ```
   (covers list/read/draft/send)
4. "Authorize APIs" → sign in with your Gmail.
5. "Exchange authorization code for tokens" → copy the `refresh_token`.

## 3. Drop credentials in the runtime

Create `runtime/secrets/gmail.json`:

```json
{
  "client_id": "...apps.googleusercontent.com",
  "client_secret": "...",
  "refresh_token": "1//..."
}
```

The runtime will use the refresh token to mint short-lived access tokens
automatically. No long-lived secret leaves your machine.

## 4. Test from in-game

Walk into a `mail-*` room and spawn an agent:

```
/omo spawn alice triage my unread mail; draft replies for anything urgent
```

The first time `gmail_send` is requested you'll get an `/omo approve` prompt.
