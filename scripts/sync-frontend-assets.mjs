import { copyFileSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";

const source = resolve("node_modules/htmx.org/dist/htmx.min.js");
const target = resolve("resources/public/static/js/htmx.min.js");

mkdirSync(dirname(target), { recursive: true });
copyFileSync(source, target);
