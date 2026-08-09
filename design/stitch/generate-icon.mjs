import { mkdir, writeFile, readFile } from "node:fs/promises";
import { readFileSync, existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { StitchToolClient } from "@google/stitch-sdk";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const outDir = join(root, "stitch", "icon");
const PROJECT_ID = process.env.STITCH_PROJECT_ID || "13330119455379583671";

function loadApiKey() {
  if (process.env.STITCH_API_KEY) return process.env.STITCH_API_KEY;
  const mcp = JSON.parse(readFileSync("/Users/pradhuldev/.cursor/mcp.json", "utf8"));
  return mcp?.mcpServers?.stitch?.headers?.["X-Goog-Api-Key"];
}

function unwrap(result) {
  if (!result) return null;
  if (result.structuredContent) return result.structuredContent;
  if (Array.isArray(result.content)) {
    const texts = result.content.filter((c) => c.type === "text").map((c) => c.text);
    for (const t of texts) {
      try {
        return JSON.parse(t);
      } catch {
        /* continue */
      }
    }
  }
  return result;
}

async function download(url, dest) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`download ${res.status}`);
  const buf = Buffer.from(await res.arrayBuffer());
  await writeFile(dest, buf);
}

const apiKey = loadApiKey();
if (!apiKey) {
  console.error("No Stitch API key");
  process.exit(1);
}

const client = new StitchToolClient({ apiKey });
await mkdir(outDir, { recursive: true });

const prompt = `Create a single Android app icon for SMSpend — a private SMS expense tracker.

Requirements:
- Square 1024x1024 composition suitable as an adaptive app icon foreground+background concept
- Solid ink green background #0F3D32 filling the full square (no phone mockup, no UI chrome, no status bars)
- Centered simple mark: a soft mint (#A8E6CF) rupee "₹" glyph OR abstract SMS+spend mark (bubble + subtle rupee), minimal geometric
- No text/wordmark, no purple, no gradients that look fintech-neon, no 3D glossy effects
- Flat, calm, stationery-utility aesthetic matching Fiscal Utility / Organic Minimalism
- Safe zone: keep mark within center 70% so Android adaptive crop works
- Output ONLY the icon artwork full-bleed square, not a screen mockup`;

console.log("Generating SMSpend app icon via Stitch…");
const gen = unwrap(
  await client.callTool("generate_screen_from_text", {
    projectId: PROJECT_ID,
    prompt,
    deviceType: "MOBILE",
  }),
);

await writeFile(join(outDir, "raw.json"), JSON.stringify(gen, null, 2));

const screenId =
  gen?.screenId ||
  gen?.screen?.id ||
  gen?.screens?.[0]?.id ||
  gen?.outputComponents?.[0]?.design?.screens?.[0]?.id;

console.log("screenId", screenId);

let screenshotUrl =
  gen?.screenshotUrl ||
  gen?.screen?.screenshot?.downloadUrl ||
  gen?.outputComponents?.[0]?.design?.screens?.[0]?.screenshot?.downloadUrl;

if (!screenshotUrl && screenId) {
  const detail = unwrap(
    await client.callTool("get_screen", { projectId: PROJECT_ID, screenId }),
  );
  await writeFile(join(outDir, "detail.json"), JSON.stringify(detail, null, 2));
  screenshotUrl =
    detail?.screenshot?.downloadUrl ||
    detail?.screen?.screenshot?.downloadUrl ||
    detail?.screenshotUrl;
}

if (screenshotUrl) {
  await download(screenshotUrl, join(outDir, "icon-source.png"));
  console.log("Saved", join(outDir, "icon-source.png"));
} else {
  console.error("No screenshot URL in Stitch response");
  process.exit(2);
}
