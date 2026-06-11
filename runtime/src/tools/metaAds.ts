import type { ToolImpl } from "./registry.js";

const GRAPH = "https://graph.facebook.com/v21.0";

function token(): string {
  const t = process.env.META_ADS_ACCESS_TOKEN ?? "";
  if (!t) throw new Error("META_ADS_ACCESS_TOKEN not set in runtime/.env");
  return t;
}

function account(): string {
  const a = process.env.META_ADS_ACCOUNT_ID ?? "";
  if (!a) throw new Error("META_ADS_ACCOUNT_ID not set (looks like 'act_123456').");
  return a.startsWith("act_") ? a : `act_${a}`;
}

async function graph(pathStr: string, init: RequestInit = {}): Promise<unknown> {
  const sep = pathStr.includes("?") ? "&" : "?";
  const url = `${GRAPH}${pathStr}${sep}access_token=${encodeURIComponent(token())}`;
  const res = await fetch(url, init);
  if (!res.ok) throw new Error(`meta ${pathStr} -> ${res.status}: ${await res.text()}`);
  return res.json();
}

export const listCampaignsTool: ToolImpl = {
  def: {
    type: "function",
    function: {
      name: "meta_ads_list_campaigns",
      description: "List Meta ad campaigns for the configured ad account with status and daily budget.",
      parameters: {
        type: "object",
        properties: {
          limit: { type: "number", default: 25 },
          effective_status: {
            type: "array",
            items: { type: "string" },
            description: "Optional filter, e.g. ['ACTIVE','PAUSED'].",
          },
        },
      },
    },
  },
  async run(args) {
    const limit = Math.min(100, Number(args.limit ?? 25));
    const fields = "id,name,status,effective_status,daily_budget,lifetime_budget,objective";
    let qs = `?fields=${fields}&limit=${limit}`;
    if (Array.isArray(args.effective_status) && args.effective_status.length) {
      qs += `&effective_status=${encodeURIComponent(JSON.stringify(args.effective_status))}`;
    }
    return graph(`/${account()}/campaigns${qs}`);
  },
};

export const insightsTool: ToolImpl = {
  def: {
    type: "function",
    function: {
      name: "meta_ads_insights",
      description: "Get performance insights (spend, impressions, clicks, ctr, cpc) for a campaign or the whole account.",
      parameters: {
        type: "object",
        properties: {
          campaign_id: { type: "string", description: "Optional. Omit to get account-level insights." },
          date_preset: {
            type: "string",
            description: "yesterday, last_7d, last_30d, this_month, etc.",
            default: "last_7d",
          },
        },
      },
    },
  },
  async run(args) {
    const fields = "spend,impressions,clicks,ctr,cpc,cpm,reach";
    const datePreset = String(args.date_preset ?? "last_7d");
    const id = args.campaign_id ? String(args.campaign_id) : account();
    return graph(`/${id}/insights?fields=${fields}&date_preset=${datePreset}`);
  },
};

export const pauseTool: ToolImpl = {
  def: {
    type: "function",
    function: {
      name: "meta_ads_pause",
      description: "Pause or resume a specific campaign. Requires owner approval.",
      parameters: {
        type: "object",
        properties: {
          campaign_id: { type: "string" },
          status: { type: "string", enum: ["PAUSED", "ACTIVE"] },
        },
        required: ["campaign_id", "status"],
      },
    },
  },
  needsApproval: () => true,
  async run(args) {
    const body = new URLSearchParams({ status: String(args.status) });
    return graph(`/${args.campaign_id}`, { method: "POST", body });
  },
};

export const updateBudgetTool: ToolImpl = {
  def: {
    type: "function",
    function: {
      name: "meta_ads_update_budget",
      description: "Set the daily_budget (cents) on a campaign. Requires owner approval.",
      parameters: {
        type: "object",
        properties: {
          campaign_id: { type: "string" },
          daily_budget_cents: { type: "number", description: "e.g. 5000 = $50/day" },
        },
        required: ["campaign_id", "daily_budget_cents"],
      },
    },
  },
  needsApproval: () => true,
  async run(args) {
    const body = new URLSearchParams({ daily_budget: String(args.daily_budget_cents) });
    return graph(`/${args.campaign_id}`, { method: "POST", body });
  },
};

export const metaAdsTools = [listCampaignsTool, insightsTool, pauseTool, updateBudgetTool];
