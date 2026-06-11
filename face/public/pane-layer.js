// No-op pane layer for the omo-mc face. The real omo pane-layer mounts
// full-stage iframes (show_pane({page:'hq'|'squad'|…})). The face has no
// internal HQ / squad pages — those live in Minecraft itself — so this
// stays a stub that won't throw if the cylinder forwards a pane payload.

export function installPaneLayer(_opts = {}) {
  if (typeof window === 'undefined') return;
  window.__holoPane = window.__holoPane || {
    mount()          { /* no-op */ },
    dismount()       { /* no-op */ },
    forwardMessage() { /* no-op */ },
  };
}

export function forwardOpenDetail(_payload) {
  return false;
}
