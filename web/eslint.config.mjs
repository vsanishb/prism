import nextVitals from "eslint-config-next/core-web-vitals";
import nextTypescript from "eslint-config-next/typescript";

const config = [
  ...nextVitals,
  ...nextTypescript,
  { ignores: [".next/**", "playwright-report/**", "test-results/**", "next-env.d.ts"] },
  { rules: { "@typescript-eslint/no-explicit-any": "error" } },
];

export default config;
