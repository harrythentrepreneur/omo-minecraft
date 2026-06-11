// Pushes REAL Meta Ads data into the live dashboard so an in-world cinema wall
// pointed at http://<host>:8088/dash/growth shows the actual ad account — the
// "live, designed website on the wall" beat. Refreshes on a gentle interval
// (Meta rate limits) and fails soft (a transient API error just skips a tick).
//
// Wire-in: call startDashboardFeed() once from server.ts after the dashboard
// server starts. Point a cinema at /dash/growth via `/omo cinema <url>`.

import { setDashboardData, type DashboardData } from "./dashboardServer.js";
import { listCampaignsTool, insightsTool } from "./tools/metaAds.js";

const GRAPH = "https://graph.facebook.com/v21.0";
const REFRESH_MS = Number(process.env.OMO_DASH_REFRESH_MS ?? 60_000);

const ctx = {
  agentId: "omo-dash",
  ownerName: "omo",
  room: "hq",
  requestApproval: async () => true,
  log: () => {},
} as never;

function money(n: number): string {
  return `$${n.toLocaleString("en-US", { maximumFractionDigits: n >= 100 ? 0 : 2 })}`;
}
function intc(n: number): string {
  return Math.round(n).toLocaleString("en-US");
}

async function dailySpend(): Promise<number[]> {
  const token = process.env.META_ADS_ACCESS_TOKEN ?? "";
  let account = process.env.META_ADS_ACCOUNT_ID ?? "";
  if (!token || !account) return [];
  if (!account.startsWith("act_")) account = `act_${account}`;
  const url =
    `${GRAPH}/${account}/insights?fields=spend&time_increment=1&date_preset=last_14d` +
    `&access_token=${encodeURIComponent(token)}`;
  try {
    const res = await fetch(url);
    if (!res.ok) return [];
    const j = (await res.json()) as { data?: { spend?: string }[] };
    return (j.data ?? []).map((d) => Number(d.spend ?? 0));
  } catch {
    return [];
  }
}

async function refresh(): Promise<void> {
  try {
    const ins = (await insightsTool.run({ date_preset: "last_30d" }, ctx)) as {
      data?: Record<string, string>[];
    };
    const m = ins.data?.[0];
    if (!m) return;

    const spend = Number(m.spend ?? 0);
    const clicks = Number(m.clicks ?? 0);
    const ctr = Number(m.ctr ?? 0);
    const cpc = Number(m.cpc ?? 0);
    const reach = Number(m.reach ?? 0);
    const impressions = Number(m.impressions ?? 0);

    const camps = (await listCampaignsTool.run({ limit: 6 }, ctx)) as {
      data?: { name: string; status: string; daily_budget?: string; objective?: string }[];
    };
    const rows = (camps.data ?? []).slice(0, 6).map((c) => [
      c.name.replace(/^PhonicsMaker - /, "").slice(0, 28),
      c.status,
      c.daily_budget ? money(Number(c.daily_budget) / 100) + "/d" : "—",
    ]);

    const series = await dailySpend();

    const data: DashboardData = {
      title: "Growth · PhonicsMaker Ads",
      subtitle: "Live Meta Ads performance — last 30 days",
      status: "LIVE",
      kpis: [
        { label: "Spend", value: money(spend), trend: "flat" },
        { label: "CTR", value: ctr.toFixed(2), unit: "%", trend: ctr >= 2 ? "up" : "down" },
        { label: "CPC", value: money(cpc), trend: cpc <= 1 ? "up" : "down" },
        { label: "Reach", value: intc(reach), trend: "up" },
        { label: "Clicks", value: intc(clicks), trend: "up" },
        { label: "Impressions", value: intc(impressions), trend: "up" },
      ],
      series: series.length ? [{ name: "Daily spend", color: "#27e7ff", points: series }] : undefined,
      table: rows.length ? { columns: ["Campaign", "Status", "Budget"], rows } : undefined,
      feed: [
        { ts: "now", text: "Growth agent pulled live insights via MCP", tone: "good" },
        { text: `${intc(impressions)} impressions · ${intc(clicks)} clicks`, tone: "info" },
        { text: `Reach ${intc(reach)} at ${money(cpc)} CPC`, tone: "info" },
      ],
      updatedAt: Date.now(),
    };
    setDashboardData("growth", data);
  } catch {
    // soft-fail: keep the last good frame on the wall
  }
}

let started = false;
export function startDashboardFeed(): void {
  if (started) return;
  started = true;
  void refresh();
  setInterval(() => void refresh(), REFRESH_MS);
}
