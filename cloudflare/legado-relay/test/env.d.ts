import type { Env as RelayEnv } from "../src/types";

declare global {
  namespace Cloudflare {
    interface Env extends RelayEnv {}
  }
}

export {};
