import { mkdir, writeFile, readFile } from "node:fs/promises";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { StitchToolClient } from "@google/stitch-sdk";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const outDir = join(root, "stitch", "screens");
const PROJECT_ID = process.env.STITCH_PROJECT_ID || "13330119455379583671";

function loadApiKey() {
  if (process.env.STITCH_API_KEY) return process.env.STITCH_API_KEY;
  const mcp = JSON.parse(readFileSync("/Users/pradhuldev/.cursor/mcp.json", "utf8"));
  return mcp?.mcpServers?.stitch?.headers?.["X-Goog-Api-Key"];
}

const DESIGN_MD = await readFile(join(root, "DESIGN.md"), "utf8");

const screens = [
  {
    id: "home",
    prompt: `SMSpend Android HOME screen.

Product: private SMS-based weekly expense tracker for India (UPI/bank debit SMS).

Layout:
- Top bar: wordmark "SMSpend" left, settings gear right
- Hero block on soft paper background: small uppercase label "THIS WEEK", large total "₹12,480", subtitle "Aug 4 – Aug 8 · scanned just now"
- Section header "By category"
- Category rows with name, merchant subtitle, amount on right, thin proportional mint bar under each:
  Food delivery · Swiggy, Zomato · ₹4,210
  Shopping · Amazon, Myntra · ₹3,650
  Grocery · BigBasket · ₹2,480
  Fuel · HPCL · ₹1,200
  Other · ₹940
- Quiet, readable money app — not a neon fintech dashboard, no purple, no glassmorphism
- Use DM Sans throughout. Debit amounts in warm red-brown. Primary ink green + mint accents on paper cream background.
High-fidelity mobile UI mockup.`,
  },
  {
    id: "category",
    prompt: `SMSpend Android CATEGORY DETAIL screen for "Food delivery".

- Back chevron + title Food delivery
- Subtotal ₹4,210 with "8 payments · 2 merchants"
- Transaction list rows: merchant, date/time, amount (₹)
  Swiggy yesterday 8:42 PM ₹486
  Zomato yesterday 1:10 PM ₹320
  Swiggy Wed 9:05 PM ₹612
  Swiggy Instamart Tue 7:18 PM ₹890
  Zomato Mon 12:40 PM ₹275
Same SMSpend design system: DM Sans, ink green #0F3D32, mint #A8E6CF, paper #F3F0E8, debit #B54A3A. No purple.`,
  },
  {
    id: "retag",
    prompt: `SMSpend Android RETAG flow: dimmed category list behind, bottom sheet in front.

Sheet:
- Title "Change category"
- Subtitle "Swiggy Instamart · ₹890"
- Category chips/grid; "Grocery" selected
- Checkbox checked: "Apply to all similar this month (Swiggy Instamart)"
- Primary full-width Save button (ink green bg, mint text)
DM Sans, paper/ink/mint palette, refined Material bottom sheet.`,
  },
  {
    id: "settings",
    prompt: `SMSpend Android SETTINGS screen.

- Back + Settings title
- Section Gemini API key: helper "Used only for unknown merchants. Your key stays on this phone." masked password field
- Section SMS scanning: "Last sync · today 12:40 PM · from Aug 1"
- Primary button Rescan SMS now
- Secondary ghost button Clear merchant memory
DM Sans, ink/mint/paper palette, calm private settings UI.`,
  },
  {
    id: "onboarding",
    prompt: `SMSpend Android ONBOARDING first-launch screen.

- Centered abstract app mark (ink green + mint, SMS/spend glyph, no letters in icon)
- Title SMSpend
- Subtitle: See this week's spending from your bank SMS — private, on this phone.
- Two quiet step cards: 1 Allow SMS (read debit alerts only) 2 Add Gemini key (smart categories)
- Primary Continue button at bottom
Warm paper atmosphere, DM Sans, no marketing clutter.`,
  },
];

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
    return { raw: texts.join("\n"), content: result.content };
  }
  return result;
}

function pickDeep(obj, keys) {
  if (!obj || typeof obj !== "object") return undefined;
  for (const k of keys) {
    if (obj[k]) return obj[k];
  }
  for (const v of Object.values(obj)) {
    if (v && typeof v === "object") {
      const found = pickDeep(v, keys);
      if (found) return found;
    }
  }
  return undefined;
}

async function download(url, dest) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`Download failed ${res.status}`);
  await writeFile(dest, Buffer.from(await res.arrayBuffer()));
}

async function main() {
  const apiKey = loadApiKey();
  if (!apiKey) throw new Error("Missing Stitch API key");
  await mkdir(outDir, { recursive: true });

  const client = new StitchToolClient({ apiKey, timeout: 300_000 });
  const projectId = PROJECT_ID;
  console.log("Using project", projectId);

  console.log("Creating design system…");
  const ds = unwrap(
    await client.callTool("create_design_system", {
      projectId,
      designSystem: {
        displayName: "SMSpend",
        theme: {
          colorMode: "LIGHT",
          headlineFont: "DM_SANS",
          bodyFont: "DM_SANS",
          labelFont: "DM_SANS",
          roundness: "ROUND_TWELVE",
          customColor: "#0F3D32",
          colorVariant: "FIDELITY",
          overridePrimaryColor: "#0F3D32",
          overrideSecondaryColor: "#3D9B7A",
          overrideTertiaryColor: "#A8E6CF",
          overrideNeutralColor: "#F3F0E8",
          designMd: DESIGN_MD,
        },
      },
    })
  );
  await writeFile(join(outDir, "design-system.json"), JSON.stringify(ds, null, 2));
  const designSystem =
    pickDeep(ds, ["name", "assetId", "id"]) ||
    ds?.designSystem?.name ||
    ds?.name;
  console.log("designSystem:", designSystem);

  const manifest = { projectId, designSystem, screens: [] };

  for (const screen of screens) {
    console.log(`\nGenerating ${screen.id}…`);
    const args = {
      projectId,
      prompt: screen.prompt,
      deviceType: "MOBILE",
      modelId: "GEMINI_3_FLASH",
    };
    if (designSystem && String(designSystem).startsWith("assets/")) {
      args.designSystem = designSystem;
    } else if (designSystem) {
      args.designSystem = String(designSystem).startsWith("assets/")
        ? designSystem
        : `assets/${String(designSystem).replace(/^assets\//, "")}`;
    }

    const gen = unwrap(await client.callTool("generate_screen_from_text", args));
    await writeFile(join(outDir, `${screen.id}.raw.json`), JSON.stringify(gen, null, 2));

    const screenName =
      pickDeep(gen, ["name"]) ||
      (gen?.screenId ? `projects/${projectId}/screens/${gen.screenId}` : null);
    const screenId =
      gen?.screenId ||
      (typeof screenName === "string" ? screenName.split("/").pop() : null);

    let detail = gen;
    if (screenName) {
      try {
        detail = unwrap(
          await client.callTool("get_screen", {
            name: screenName,
            projectId,
            screenId: screenId || "unknown",
          })
        );
        await writeFile(
          join(outDir, `${screen.id}.detail.json`),
          JSON.stringify(detail, null, 2)
        );
      } catch (e) {
        console.warn("get_screen:", e.message);
      }
    }

    const htmlUrl = pickDeep(detail, [
      "downloadUrl",
      "htmlCodeDownloadUrl",
    ]);
    // Prefer explicit html paths
    const html =
      detail?.htmlCode?.downloadUrl ||
      detail?.screen?.htmlCode?.downloadUrl ||
      pickDeep(detail?.htmlCode || {}, ["downloadUrl"]);
    const image =
      detail?.screenshot?.downloadUrl ||
      detail?.screen?.screenshot?.downloadUrl ||
      detail?.screenshotDownloadUrl ||
      pickDeep(detail, ["screenshotDownloadUrl"]);

    // Brute search download URLs in JSON
    const blob = JSON.stringify(detail);
    const urls = [...blob.matchAll(/https:\/\/[^"\\]+/g)].map((m) => m[0]);
    const htmlCandidate =
      html || urls.find((u) => /html|code|text\/html/i.test(u)) || null;
    const imgCandidate =
      image || urls.find((u) => /\.png|screenshot|image/i.test(u)) || null;

    if (htmlCandidate) {
      try {
        await download(htmlCandidate, join(outDir, `${screen.id}.html`));
      } catch (e) {
        console.warn("html download:", e.message);
      }
    }
    if (imgCandidate) {
      try {
        await download(imgCandidate, join(outDir, `${screen.id}.png`));
      } catch (e) {
        console.warn("image download:", e.message);
      }
    }

    // Also capture any fileData / base64 in content
    const rawFile = join(outDir, `${screen.id}.raw.json`);
    const rawText = await readFile(rawFile, "utf8");
    const b64 = rawText.match(/"data:image\/png;base64,([^"]+)"/);
    if (b64) {
      await writeFile(join(outDir, `${screen.id}.png`), Buffer.from(b64[1], "base64"));
    }

    manifest.screens.push({
      id: screen.id,
      screenName,
      screenId,
      html: !!htmlCandidate,
      image: !!imgCandidate,
      htmlCandidate,
      imgCandidate,
    });
    console.log("Done", screen.id, { screenName, html: !!htmlCandidate, image: !!imgCandidate });
  }

  await writeFile(join(outDir, "manifest.json"), JSON.stringify(manifest, null, 2));
  await client.close?.();
  console.log("\nWrote", join(outDir, "manifest.json"));
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
