import { cloudflarePool } from "@cloudflare/vitest-pool-workers";
import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    include: ["test/relay.test.ts"],
    pool: cloudflarePool({
      wrangler: { configPath: "./wrangler.toml" },
      miniflare: {
        bindings: {
          RELAY_ADMIN_TOKEN: "test-admin-token-with-at-least-32-characters",
          RELAY_CREDENTIAL_KEY: "ERERERERERERERERERERERERERERERERERERERERERE",
          RELAY_ROUTE_KEY: "IiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiI",
        },
      },
    }),
  },
});
