# Meta Ads setup

The ads tools use the Meta Marketing API directly.

## 1. Get an access token

1. https://developers.facebook.com → Apps → Create app → "Business" type.
2. Add the **Marketing API** product to the app.
3. In Tools → Graph API Explorer, select your app, then permissions:
   - `ads_read`
   - `ads_management`
   - `business_management`
4. Generate a short-lived token.
5. Exchange it for a long-lived token:
   ```bash
   curl -G https://graph.facebook.com/v21.0/oauth/access_token \
     -d grant_type=fb_exchange_token \
     -d client_id=<APP_ID> \
     -d client_secret=<APP_SECRET> \
     -d fb_exchange_token=<SHORT_LIVED_TOKEN>
   ```
   The result is a ~60-day token. For longer-lived, generate a System User token
   from Business Settings.

## 2. Find your ad account ID

Business Manager → Ads Manager URL contains `act=<id>`, or:

```bash
curl "https://graph.facebook.com/v21.0/me/adaccounts?access_token=<TOKEN>"
```

Use the `act_XXXXXX` form.

## 3. Configure the runtime

In `runtime/.env`:

```
META_ADS_ACCESS_TOKEN=EAAB...
META_ADS_ACCOUNT_ID=act_1234567890
```

## 4. Use from in-game

```
/omo room define ads-room
/omo spawn bob watch CPC across active campaigns and pause anything above $5
```

Pausing or changing budgets always triggers an `/omo approve` prompt.
