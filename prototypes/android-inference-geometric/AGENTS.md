# Prototype Instructions

## Owner direction

- 8.8-inch Android inference-node concept preview for 16:10 tablets; implement with Web technology only. Do not change Android native source.
- Preserve the selected geometric composition. Use the exact neutral-gray/cyan project palette and official Lucide 1.49.0 icons.
- Simulate physical manipulation through flat geometry, clipping, translation and scale: bounded vertical FPS drum, confidence rail, decimal cooldown counter, lens/theme shifters, piston-like action keys and sliding drawers. No decorative 3D shading or gradients.
- Base contracts on the inherited `dsh/android-inference` code. Keep preview selection independent of source running state; lock cold changes during inference. All data and operation results in this prototype are simulated, with no production connection or credential persistence.
- Every existing business control must appear in the control inventory, concept family, interactive design sample and scoped design specification. Concepts illustrate intent; current business contracts and exact palette take priority over incidental generated text or chrome.

Run the local server yourself and open the preview in the browser available to this environment. Do not give the user server-start instructions when you can run it.

Before making substantial visual changes, use the Product Design plugin's `get-context` skill when the visual source is unclear or no longer matches the current goal. When the user gives durable prototype-specific design feedback, preferences, or decisions, record them in `AGENTS.md`.

When implementing from a selected generated mock, treat that image as the source of truth for layout, component anatomy, density, spacing, color, typography, visible content, and hierarchy.

Build app UI in `src/`. Keep `.openai/hosting.json`, `worker/index.js`, `scripts/prepare-sites-build.mjs`, and `tests/sites-worker.test.mjs` intact so the same local prototype can be handed to Sites. Before a Sites handoff, run `npm run build` and `npm run test:sites`; the build must leave `dist/client/index.html`, `dist/server/index.js`, and `dist/.openai/hosting.json`.
