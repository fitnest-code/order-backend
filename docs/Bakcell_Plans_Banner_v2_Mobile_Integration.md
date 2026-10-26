# Bakcell plans banner v2 mobile integration

Audience: FitNest Android and iOS developers. Prepared 26 October 2026.

## Scope agreed for this release

Add a Bakcell banner and localized offer text to the subscription plan selection screen. The backend decides visibility using the authenticated account's stored phone number. Admins can upload images, edit AZ/EN/RU copy, select packages and durations, and schedule or disable the banner.

This release is **presentation only**. It does not apply a 20 percent discount, modify plan prices, create a promo grant, consume a coupon, change FitCoin calculations, or initiate a payment. Keep existing checkout and price data. Do not infer payable amounts from banner text or from this API. Discount-payment integration is a separate phase explicitly excluded from this release.

Mobile repositories were inspected without modification:

| Platform | Reviewed remote branch | Commit |
| --- | --- | --- |
| Android | development | 1d0475459e1a1eb6d6f239ce072cc9d5356adcd6 |
| iOS | main | fe3b79f026d6c139ed5cd704b3f2e68854ab96b0 |

## Current implementation findings

Android `SubscriptionPackagesViewModel.kt` requests `GET /api/v1/campaigns/active?context=banner`. `SubscriptionPackagesScreen.kt` renders the returned October campaign through `CampaignBannerCard`. The app already has separate Home promo banners and checkout coupon models, but the plan-selection screen does not request the new endpoint below.

iOS `SubscriptionPlansViewController.swift`, in `loadPackages`, loads packages alongside the same October campaign request. `configureCampaignBanner` inserts `CampaignPlanBannerView` above the package chips. `PromoFeature.isEnabled` is true on the reviewed main branch, and `PromoDebug.enabled` is false. Existing Home/checkout promo code is separate from this plan-banner placement.

Both apps send `packageId` to the old active-promo API, while its existing controller declares `planId`. Do not copy that mismatch into the new integration: the endpoint below explicitly accepts `packageId`.

The old October campaign and all v1 APIs stay in place. Do not replace the October API response with the new DTO: October uses a numeric campaign ID and bonus-month offers; this API uses a UUID and presentation content.

## API

Production base: `https://api.fitnest.az`

Development base: `https://api-dev.fitnest.az`

```http
GET /api/v2/me/plan-campaigns?packageId=4
Authorization: Bearer <access-token>
Accept-Language: az
```

`packageId` is optional. When supplied, it is the numeric package ID returned by the existing package catalog. Do not send a phone number, prefix, user ID, campaign ID, or discount percentage. These values are not client-controlled eligibility inputs.

Supported content languages are `az`, `en`, and `ru`. Headers such as `en-US,en;q=0.9` are accepted. If a locale is missing or incomplete, the whole content block falls back to AZ and `locale` reports `az`.

Responses set `Cache-Control: no-store` and vary by Authorization and Accept-Language. Do not cache results across accounts or persist them as shared static configuration. Clear campaign UI state on logout and account changes.

### Eligible response example

This is an illustrative enabled response, not evidence that a live campaign has been enabled. Media URLs must come from actual uploaded assets.

```json
{
  "schemaVersion": 2,
  "eligible": true,
  "items": [
    {
      "id": "bacc3110-2026-4000-8000-000000000001",
      "code": "BAKCELL_PLANS",
      "locale": "az",
      "content": {
        "heading": "Bakcell-ə özəl kampaniya",
        "subtitle": "3, 6 və 12 aylıq planlarda xüsusi təkliflər",
        "specialOffersTitle": "Xüsusi təkliflər",
        "bannerImageUrl": "https://media.fitnest.az/stream/123456789",
        "imageAlt": "Bakcell-ə özəl kampaniya"
      },
      "packageIds": [],
      "durationMonths": [3, 6, 12],
      "endsAt": "2026-11-20T23:59:00+04:00",
      "placement": "PLANS_TOP_AND_BEFORE_ELIGIBLE_DURATIONS",
      "pricingEffect": "NONE"
    }
  ]
}
```

Optional content fields: `darkBannerImageUrl`, `iconUrl`, and `darkIconUrl`. Null fields may be omitted. An empty `packageIds` array means every package, including packages added later. `durationMonths` identifies the duration cards that belong under the offer label. It does not indicate a billing discount.

### Hidden response

```json
{"schemaVersion":2,"eligible":false,"items":[]}
```

Return the normal plan-selection UI with no Bakcell banner, icon, offer heading, or placeholder gap. This response covers other phone prefixes, missing phone data, directory lookup failure, disabled/scheduled/expired campaigns, a restricted package, and missing mandatory AZ content. The API does not expose the user's number or the reason for audience exclusion.

## Eligibility rules

The gateway authenticates the request and forwards the account identity. Order backend resolves that account through user backend's internal directory and checks its stored phone. It never trusts a number supplied by the app.

Eligible normalized numbers match `+99455XXXXXXX` or `+99499XXXXXXX`. Supported stored formats include `0551234567`, `0991234567`, `551234567`, `+994551234567`, `994991234567`, and `00994551234567`, with common spaces and punctuation. Malformed numbers and other prefixes do not qualify.

This is prefix-based targeting, not a check of the number's current telecom operator. The directory exposes the account's stored phone, not a separate `mobileVerified` flag; the implementation relies on the existing account registration and phone-change verification flow. No device SIM or client-side carrier check is required.

Visibility also requires `enabled=true`, a complete AZ content block, and a current time in `[startsAt, endsAt)`. The server checks timestamps; the phone clock is not an eligibility authority. Active subscriptions do not suppress this banner in this phase. No uploaded phone list or pre-existing promo grant is required.

## Layout mapping to the reference

1. Place `bannerImageUrl` above Bronze/Silver/Gold/Platinum package chips. Recommended source ratio is 987 by 432. Scale proportionally with rounded corners; do not stretch the artwork.
2. In dark mode, prefer `darkBannerImageUrl`, falling back to `bannerImageUrl`.
3. Place the optional icon beside `content.heading` and `content.subtitle`, before the first eligible duration card. In dark mode prefer `darkIconUrl`, then `iconUrl`.
4. Use `specialOffersTitle` as the section label if the design includes a separate special-offers heading. Avoid rendering duplicate headings.
5. Use `durationMonths` rather than hardcoding 3, 6, and 12. Keep ineligible duration cards in their ordinary section.
6. On package changes, refetch with the selected `packageId`. Discard responses from an older package selection or account session. Hide the previous banner while a different package's eligibility is unresolved.
7. If the request fails, continue rendering packages and checkout. Do not fall back to bundled Bakcell artwork or a mock Bakcell campaign for a user whose eligibility is unknown.
8. If an image fails to load, omit its image view without reserving an empty image frame. The eligible text block may remain visible. Use `imageAlt` for accessibility; decorative icons should not repeat the heading to screen readers.
9. This phase defines no banner tap action or coupon selection. A tap must not initiate payment, automatically apply a coupon, or alter the displayed price.

When both October and Bakcell campaigns are returned by their respective endpoints, render the Bakcell artwork at the top and retain October bonus-month labels on the affected existing duration cards. Avoid two large stacked hero banners. Suppression of October business benefits is not authorized by this presentation response.

## Android changes to make

In `FitnestMainApi.kt`, add a separate endpoint and DTOs rather than reusing `CampaignResponseDto`:

```kotlin
@GET("api/v2/me/plan-campaigns")
suspend fun getPlanCampaigns(
    @Query("packageId") packageId: Long? = null
): PlanCampaignsV2Dto
```

Add a repository/use-case method, then load it from `SubscriptionPackagesViewModel` after the package selection is known. Use a cancellable job or a request generation key on `onChangePlan`; an old response must not overwrite the current selection. Add explicit state for the returned v2 item, separate from the existing October `campaign` state.

In `SubscriptionPackagesScreen`, add an image banner above package chips and the server-provided offer heading before matching duration cards. Consume optional theme images safely. Preserve existing selection, subscription status, coin previews, and navigation to payment.

## iOS changes to make

Add a separate endpoint using the root API base with the full `/api/v2/me/plan-campaigns` path. `NetworkingHelper.fullBaseURL` already includes `/api/v1`; do not append the v2 path to that value or create `/api/v1/api/v2/...`.

Create `Decodable` DTOs with UUIDs represented as `String` or `UUID`, optional theme-image fields, and integer package/duration arrays. In `SubscriptionPlansViewController`, request content for the current tier in addition to existing package and October reads. Cancel or discard outdated results when the selected tier changes.

Insert the artwork above the chips using its aspect ratio. Render the icon and offer text immediately before eligible duration rows. Remove old banner constraints before installing new ones; avoid leaving a fixed height when the response is empty. Reconfigure light/dark assets on trait changes without caching eligibility across accounts.

## Admin content management

Open `/campaign?mainTab=planBanners` in the relevant admin environment. The new Mobile banners tab lists the seeded `BAKCELL_PLANS` record. There is no campaign-creation requirement for this phase.

Admins can edit the internal name, enable/disable visibility, set start/end timestamps in Baku time, select packages and duration months, edit localized text, paste HTTPS media URLs, or upload PNG/JPEG/WebP files up to 8 MB. Light and dark previews are provided. Uploading an image stores it; Save attaches the returned URL to the campaign.

The seeded record is disabled and has no images or schedule. Enabling requires AZ heading, subtitle, special-offers heading, alt text, an HTTPS banner URL, valid dates, and at least one duration. EN/RU are optional and use AZ fallback until complete. Admin content validation does not verify whether marketing copy is commercially approved.

| Method | Endpoint | Purpose |
| --- | --- | --- |
| GET | `/api/v2/admin/plan-campaigns` | List editable presentation campaigns |
| GET | `/api/v2/admin/plan-campaigns/{id}` | Read one record and its version |
| PUT | `/api/v2/admin/plan-campaigns/{id}` | Save content, schedule, visibility, and targeting |
| POST | `/api/v2/admin/plan-campaigns/images` | Multipart image upload using part name `file` |

Admin routes require ADMIN. PUT is a full update and must include the most recently read `version`. A stale version returns 409; reload and reapply the intended edit. Prefix targeting is fixed server-side and is not editable in the admin request.

```json
{
  "version": 0,
  "name": "Bakcell plan banneri",
  "enabled": false,
  "startsAt": null,
  "endsAt": null,
  "packageIds": [],
  "durationMonths": [3, 6, 12],
  "content": {
    "az": {
      "heading": "Bakcell-ə özəl kampaniya",
      "subtitle": "3, 6 və 12 aylıq planlarda xüsusi təkliflər",
      "specialOffersTitle": "Xüsusi təkliflər",
      "imageAlt": "Bakcell-ə özəl kampaniya"
    }
  }
}
```

Image upload returns `{"url":"https://…/stream/123456789","fsId":"123456789"}`. URLs are environment-specific: do not copy development storage URLs into production. Uploaded images are public marketing assets; audience filtering controls API visibility, not confidentiality of the image bytes.

## Acceptance checks for mobile developers

| Scenario | Required result |
| --- | --- |
| 055 or 099 account, enabled campaign within dates | Banner and server-provided text appear |
| 050, 051, 070, 077, other or malformed number | No Bakcell content or placeholder gaps |
| Directory unavailable | Existing plans remain usable and Bakcell is hidden |
| Missing/incomplete EN/RU | Complete AZ content block is returned |
| Dark asset missing | Light image/icon fallback |
| Restricted package | Hidden on other packages |
| Exactly at end timestamp | Hidden |
| Fast tier switches or account change | No stale banner from the previous request |
| Admin changes text/image, then screen is reopened | Fresh content is loaded |
| October campaign still applies | Bonus-month behavior is preserved |
| Banner text mentions an offer | Checkout prices still come from existing pricing APIs |

The mobile apps must implement and release this integration before users can see the new placement. Deploying backend and admin alone does not add a new view to already installed app binaries. Final banner artwork and an approved campaign schedule must be configured before enabling production visibility.
