'use client';

import { useEffect } from 'react';
import { startForms, startLists, startMenus, startMotion } from '@/lib/site-scripts';

const started = new Set<string>();

/**
 * Starts the site's page behaviour (entrance animations, folding menus, forms that send) once the
 * page is on screen. Each starts once per page load, also when React runs effects twice in development.
 */
export default function SiteScripts({ motion = false, menu = false, forms = false, lists = false }: { motion?: boolean; menu?: boolean; forms?: boolean; lists?: boolean }) {
  useEffect(() => {
    const start = (name: string, on: boolean, run: () => void) => {
      if (!on || started.has(name)) return;
      started.add(name);
      run();
    };
    start('motion', motion, startMotion);
    start('menu', menu, startMenus);
    start('forms', forms, startForms);
    start('lists', lists, startLists);
  }, [motion, menu, forms, lists]);
  return null;
}
