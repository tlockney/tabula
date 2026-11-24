import { TabulaPanel } from "./shell";

/** The element name panel_custom is told to load (const.PANEL_WEBCOMPONENT). */
export const ELEMENT_NAME = "tabula-panel";

if (!customElements.get(ELEMENT_NAME)) {
  customElements.define(ELEMENT_NAME, TabulaPanel);
}

declare global {
  interface HTMLElementTagNameMap {
    "tabula-panel": TabulaPanel;
  }
}
