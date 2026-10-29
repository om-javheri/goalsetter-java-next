import type { NextConfig } from 'next';

// A static site in dist/, every page at the same path as on the HTML site (about/index.html).
// BASE_PATH serves it under a sub-path, e.g. BASE_PATH=/repo for a GitHub Pages project site.
const nextConfig: NextConfig = {
  output: 'export',
  trailingSlash: true,
  basePath: process.env.BASE_PATH ?? '',
  distDir: 'dist',
};

export default nextConfig;
