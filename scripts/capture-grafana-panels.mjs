import { createHash } from 'node:crypto';

// Grafana panel capture helper for capacity experiments.
//
// Why this exists: a dashboard screenshot is only evidence if the time range,
// refresh interval, panel size and Grafana version are recorded with it. The
// helper renders one panel at a time so each infrastructure signal has its own
// window and caption, then writes a manifest the experiment document can link.
//
// It talks to a Grafana instance that already has the `atlas-capacity-local`
// dashboard provisioned and an image renderer reachable by Grafana itself.
//
// Usage:
//   GRAFANA_URL=http://127.0.0.1:3000 \
//   GRAFANA_USER=admin GRAFANA_PASSWORD=... \
//   node scripts/capture-grafana-panels.mjs \
//     --run-id exp035-r218 --from 2026-11-18T08:40:00Z --to 2026-11-18T08:44:00Z \
//     --out docs/03-experiments/results/exp-035/exp035-r218
//
// Exit codes: 0 all captures written, 1 at least one capture failed.

const args = new Map();
for (let index = 2; index < process.argv.length; index += 2) {
  const key = process.argv[index];
  const value = process.argv[index + 1];
  if (!key?.startsWith('--') || value === undefined) {
    console.error(`Invalid arguments near ${key}`);
    process.exit(2);
  }
  args.set(key.slice(2), value);
}

const grafanaUrl = (process.env.GRAFANA_URL || 'http://127.0.0.1:3000').replace(/\/$/, '');
const grafanaUser = process.env.GRAFANA_USER || 'admin';
// Matches the compose default so a fresh checkout can capture without extra setup.
// Override both variables when the local stack uses different credentials.
const grafanaPassword = process.env.GRAFANA_PASSWORD || 'local-only-change-me';
const dashboardUid = process.env.GRAFANA_DASHBOARD_UID || 'atlas-capacity-local';
const runId = args.get('run-id');
const from = args.get('from');
const to = args.get('to');
const outDir = args.get('out');
const width = Number(args.get('width') || '1000');
const height = Number(args.get('height') || '360');
const theme = args.get('theme') || 'light';

if (!runId || !from || !to || !outDir) {
  console.error('Required: --run-id --from --to --out');
  process.exit(2);
}
if (!Number.isSafeInteger(width) || !Number.isSafeInteger(height) || width < 200 || height < 120) {
  console.error('--width/--height must be integers >= 200/120');
  process.exit(2);
}

// The /render endpoint silently returns "No data" when given ISO-8601 strings for
// from/to, even though /api/ds/query accepts them. Grafana's own UI sends epoch
// milliseconds, so convert here and fail loudly on a bad timestamp.
function toEpochMillis(value, label) {
  const parsed = /^\d+$/.test(value) ? Number(value) : Date.parse(value);
  if (!Number.isFinite(parsed)) {
    console.error(`Invalid ${label} timestamp: ${value}`);
    process.exit(2);
  }
  return parsed;
}

const fromMs = toEpochMillis(from, '--from');
const toMs = toEpochMillis(to, '--to');
if (toMs <= fromMs) {
  console.error('--to must be after --from');
  process.exit(2);
}

const auth = Buffer.from(`${grafanaUser}:${grafanaPassword}`).toString('base64');
const headers = { Authorization: `Basic ${auth}`, Accept: 'application/json' };

async function grafanaJson(path) {
  const response = await fetch(`${grafanaUrl}${path}`, { headers });
  if (!response.ok) {
    throw new Error(`GET ${path} failed: HTTP ${response.status}`);
  }
  return response.json();
}

// Panels to capture are read from the provisioned dashboard itself, so the
// capture list cannot drift from what an operator actually sees.
const dashboard = await grafanaJson(`/api/dashboards/uid/${dashboardUid}`);
const panels = (dashboard.dashboard.panels || [])
  .filter((panel) => Number.isInteger(panel.id) && panel.type !== 'row')
  .map((panel) => ({ id: panel.id, title: panel.title || `panel-${panel.id}` }));

if (panels.length === 0) {
  console.error(`Dashboard ${dashboardUid} exposes no capturable panels`);
  process.exit(1);
}

const { mkdir, writeFile } = await import('node:fs/promises');
await mkdir(outDir, { recursive: true });

const manifest = {
  runId,
  dashboardUid,
  dashboardVersion: dashboard.dashboard.version,
  grafanaVersion: dashboard.meta?.version,
  from,
  to,
  fromEpochMillis: fromMs,
  toEpochMillis: toMs,
  theme,
  width,
  height,
  capturedAt: new Date().toISOString(),
  panels: [],
};

let failures = 0;
for (const panel of panels) {
  const safeTitle = panel.title.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
  const file = `${outDir}/${runId}-p${String(panel.id).padStart(2, '0')}-${safeTitle}.png`;
  const url = `${grafanaUrl}/render/d-solo/${dashboardUid}/?orgId=1`
    + `&panelId=${panel.id}`
    + `&from=${fromMs}&to=${toMs}`
    + `&width=${width}&height=${height}&theme=${theme}&kiosk=1`;

  try {
    const response = await fetch(url, { headers: { Authorization: `Basic ${auth}` } });
    const contentType = response.headers.get('content-type') || '';
    if (!response.ok || !contentType.startsWith('image/')) {
      throw new Error(`HTTP ${response.status} content-type ${contentType}`);
    }
    const bytes = Buffer.from(await response.arrayBuffer());
    await writeFile(file, bytes);
    manifest.panels.push({
      id: panel.id,
      title: panel.title,
      file,
      bytes: bytes.length,
      sha256: createHash('sha256').update(bytes).digest('hex'),
    });
    console.log(`captured panel ${panel.id} (${panel.title}) -> ${file} (${bytes.length} bytes)`);
  } catch (error) {
    failures += 1;
    manifest.panels.push({ id: panel.id, title: panel.title, error: String(error) });
    console.error(`panel ${panel.id} (${panel.title}) failed: ${error}`);
  }
}

await writeFile(`${outDir}/manifest.json`, `${JSON.stringify(manifest, null, 2)}\n`);
console.log(`manifest -> ${outDir}/manifest.json`);
process.exit(failures === 0 ? 0 : 1);
