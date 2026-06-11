// No-op chart layer for the omo-mc face. The real omo chart-layer mounts
// data-visualisation iframes (render_viz / dismiss_viz / highlight_viz) onto
// the cylinder stage. None of those tools ship with the face — visualisation
// happens *inside* Minecraft on room screens, not on the hologram. So this
// module just exports the function signature the cylinder expects and
// installs a tiny shim on window so any stray calls don't throw.

export function installChartLayer(_opts = {}) {
  if (typeof window === 'undefined') return;
  window.__holoChart = window.__holoChart || {
    mount()      { /* no-op */ },
    dismount()   { /* no-op */ },
    dismissAll() { /* no-op */ },
    highlight()  { /* no-op */ },
  };
}
