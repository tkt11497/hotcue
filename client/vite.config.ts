import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import { readFileSync } from "fs";
import { resolve } from "path";

const certsDir = resolve(__dirname, "..", "server", "certs");

let serverConfig = {};
try {
  serverConfig = {
    https: {
      key: readFileSync(resolve(certsDir, "key.pem")),
      cert: readFileSync(resolve(certsDir, "cert.pem")),
    },
  };
} catch {
  console.warn("⚠ No certs found — run the setup script first. Falling back to HTTP.");
}

export default defineConfig({
  plugins: [vue()],
  server: {
    ...serverConfig,
    host: "0.0.0.0",
    port: 5173,
  },
});
