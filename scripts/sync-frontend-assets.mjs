import { copyFileSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";

const source = resolve("node_modules/htmx.org/dist/htmx.min.js");
const target = resolve("resources/public/static/js/htmx.min.js");
const plotlySource = resolve(
  "node_modules/plotly.js-dist-min/plotly.min.js"
);
const plotlyTarget = resolve("resources/public/static/js/plotly.min.js");

mkdirSync(dirname(target), { recursive: true });
copyFileSync(source, target);
copyFileSync(plotlySource, plotlyTarget);
