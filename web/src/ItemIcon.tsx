import { useEffect, useState } from "react";
import { api } from "./api";

// Draws items the way the inventory screen does, from the textures and models the panel extracts from the
// official client jar: flat sprite layers for generated items, cuboids seen through the GUI transform for blocks.
type Texture = {
  texture: string;
  tint?: string;
  uv?: [number, number, number, number];
};
type Vec = [number, number, number];
type Element = {
  from: Vec;
  to: Vec;
  rotation?: { origin: Vec; axis: "x" | "y" | "z"; angle: number };
  faces: Partial<Record<Face, Texture>>;
};
type Face = "up" | "down" | "north" | "south" | "west" | "east";
type Icon = {
  label?: string;
  layers?: Texture[];
  elements?: Element[];
  gui?: { rotation: Vec; translation: Vec; scale: Vec };
};

const SIZE = 128;
let base = "";
let unavailable = "";
const icons = new Map<string, Promise<Icon | null>>();
const images = new Map<string, Promise<HTMLImageElement | null>>();
const rendered = new Map<string, Promise<string | null>>();
const listeners = new Set<() => void>();
let pending = new Map<string, (icon: Icon | null) => void>();
let batch: ReturnType<typeof setTimeout> | undefined;

function flush() {
  batch = undefined;
  const waiting = pending;
  pending = new Map();
  const names = [...waiting.keys()];
  for (let i = 0; i < names.length; i += 100) {
    const chunk = names.slice(i, i + 100);
    api<{ base: string; icons: Record<string, Icon> }>(
      `/textures/icons?m=${encodeURIComponent(chunk.join(","))}`,
    )
      .then((v) => {
        base = v.base;
        if (unavailable) {
          unavailable = "";
          listeners.forEach((l) => l());
        }
        chunk.forEach((m) => waiting.get(m)!(v.icons[m] || null));
      })
      .catch((e) => {
        unavailable = e instanceof Error ? e.message : "Textures unavailable";
        listeners.forEach((l) => l());
        chunk.forEach((m) => {
          icons.delete(m);
          waiting.get(m)!(null);
        });
      });
  }
}
function iconFor(material: string) {
  let icon = icons.get(material);
  if (!icon) {
    icon = new Promise((resolve) => pending.set(material, resolve));
    icons.set(material, icon);
    batch ??= setTimeout(flush, 0);
  }
  return icon;
}
/** Resolves once the panel has said where textures live (any icon request does that). */
function textures() {
  return base
    ? Promise.resolve(true)
    : iconFor("minecraft:stone").then(() => !!base);
}
function image(texture: string) {
  let img = images.get(texture);
  if (!img) {
    img = new Promise((resolve) => {
      const el = new Image();
      el.onload = () => resolve(el);
      el.onerror = () => resolve(null);
      el.src = `${base}${texture}.png`;
    });
    images.set(texture, img);
  }
  return img;
}

/** Text explaining why textures are missing, or "" once they load. */
export function useTextureStatus() {
  const [status, setStatus] = useState(unavailable);
  useEffect(() => {
    const update = () => setStatus(unavailable);
    listeners.add(update);
    return () => void listeners.delete(update);
  }, []);
  return status;
}

/** Tints and shades one texture region into its own canvas; animated strips use their first frame. */
function paint(img: HTMLImageElement, uv: number[], tint = "", light = 1) {
  const k = img.width / 16;
  const x = Math.min(uv[0], uv[2]) * k,
    y = Math.min(uv[1], uv[3]) * k;
  const w = Math.max(1, Math.abs(uv[2] - uv[0]) * k),
    h = Math.max(1, Math.abs(uv[3] - uv[1]) * k);
  const c = document.createElement("canvas");
  c.width = Math.ceil(w);
  c.height = Math.ceil(h);
  const g = c.getContext("2d")!;
  g.drawImage(img, x, y, w, h, 0, 0, c.width, c.height);
  let rgb = tint ? parseInt(tint.slice(1), 16) : 0xffffff;
  if (rgb !== 0xffffff || light < 1) {
    const r = Math.round(((rgb >> 16) & 255) * light),
      gr = Math.round(((rgb >> 8) & 255) * light),
      b = Math.round((rgb & 255) * light);
    g.globalCompositeOperation = "multiply";
    g.fillStyle = `rgb(${r},${gr},${b})`;
    g.fillRect(0, 0, c.width, c.height);
    g.globalCompositeOperation = "destination-in";
    g.drawImage(img, x, y, w, h, 0, 0, c.width, c.height);
  }
  return c;
}

const corners: Record<
  Face,
  (f: Vec, t: Vec) => { p: Vec[]; uv: [number, number, number, number] }
> = {
  up: (f, t) => ({
    p: [
      [f[0], t[1], f[2]],
      [t[0], t[1], f[2]],
      [f[0], t[1], t[2]],
    ],
    uv: [f[0], f[2], t[0], t[2]],
  }),
  down: (f, t) => ({
    p: [
      [f[0], f[1], t[2]],
      [t[0], f[1], t[2]],
      [f[0], f[1], f[2]],
    ],
    uv: [f[0], 16 - t[2], t[0], 16 - f[2]],
  }),
  north: (f, t) => ({
    p: [
      [t[0], t[1], f[2]],
      [f[0], t[1], f[2]],
      [t[0], f[1], f[2]],
    ],
    uv: [16 - t[0], 16 - t[1], 16 - f[0], 16 - f[1]],
  }),
  south: (f, t) => ({
    p: [
      [f[0], t[1], t[2]],
      [t[0], t[1], t[2]],
      [f[0], f[1], t[2]],
    ],
    uv: [f[0], 16 - t[1], t[0], 16 - f[1]],
  }),
  west: (f, t) => ({
    p: [
      [f[0], t[1], f[2]],
      [f[0], t[1], t[2]],
      [f[0], f[1], f[2]],
    ],
    uv: [f[2], 16 - t[1], t[2], 16 - f[1]],
  }),
  east: (f, t) => ({
    p: [
      [t[0], t[1], t[2]],
      [t[0], t[1], f[2]],
      [t[0], f[1], t[2]],
    ],
    uv: [16 - t[2], 16 - t[1], 16 - f[2], 16 - f[1]],
  }),
};
const sub = (a: Vec, b: Vec): Vec => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
function rotate(v: Vec, axis: "x" | "y" | "z", degrees: number): Vec {
  const a = (degrees * Math.PI) / 180,
    c = Math.cos(a),
    s = Math.sin(a);
  const [x, y, z] = v;
  if (axis === "x") return [x, y * c - z * s, y * s + z * c];
  if (axis === "y") return [x * c + z * s, y, -x * s + z * c];
  return [x * c - y * s, x * s + y * c, z];
}

async function draw(icon: Icon) {
  const canvas = document.createElement("canvas");
  canvas.width = canvas.height = SIZE;
  const g = canvas.getContext("2d")!;
  g.imageSmoothingEnabled = false;
  if (icon.layers) {
    for (const layer of icon.layers) {
      const img = await image(layer.texture);
      if (!img) continue;
      const uv = layer.uv || [0, 0, 16, 16];
      // A cropped layer (the shield's front plate) keeps its proportions, centred in the slot.
      const w = Math.abs(uv[2] - uv[0]),
        h = Math.abs(uv[3] - uv[1]),
        fit = SIZE / Math.max(w, h);
      g.drawImage(
        paint(img, uv, layer.tint),
        (SIZE - w * fit) / 2,
        (SIZE - h * fit) / 2,
        w * fit,
        h * fit,
      );
    }
    return canvas.toDataURL();
  }
  const gui = icon.gui || {
    rotation: [30, 225, 0],
    translation: [0, 0, 0],
    scale: [0.625, 0.625, 0.625],
  };
  // ItemTransform order: translate, rotate X·Y·Z, scale, around the model centre; screen Y points down.
  const view = (v: Vec): Vec => {
    let p: Vec = [
      (v[0] - 8) * gui.scale[0],
      (v[1] - 8) * gui.scale[1],
      (v[2] - 8) * gui.scale[2],
    ];
    p = rotate(
      rotate(rotate(p, "z", gui.rotation[2]), "y", gui.rotation[1]),
      "x",
      gui.rotation[0],
    );
    return [
      p[0] + gui.translation[0],
      p[1] + gui.translation[1],
      p[2] + gui.translation[2],
    ];
  };
  const faces: {
    depth: number;
    points: Vec[];
    uv: number[];
    texture: Texture;
    light: number;
  }[] = [];
  for (const el of icon.elements || []) {
    const local = (v: Vec): Vec => {
      if (!el.rotation) return v;
      const o = el.rotation.origin;
      const r = rotate(sub(v, o), el.rotation.axis, el.rotation.angle);
      return [r[0] + o[0], r[1] + o[1], r[2] + o[2]];
    };
    for (const [face, texture] of Object.entries(el.faces) as [
      Face,
      Texture,
    ][]) {
      const shape = corners[face](el.from, el.to);
      const points = shape.p.map((p) => view(local(p)));
      const eu = sub(points[1], points[0]),
        ev = sub(points[2], points[0]);
      const normalZ = -(eu[0] * ev[1] - eu[1] * ev[0]);
      if (normalZ <= 1e-6) continue;
      const normalY = -(eu[2] * ev[0] - eu[0] * ev[2]);
      const length = Math.hypot(
        normalZ,
        normalY,
        eu[1] * ev[2] - eu[2] * ev[1],
      );
      const up = normalY / length;
      const left = -(eu[1] * ev[2] - eu[2] * ev[1]) / length < 0;
      faces.push({
        depth: points[0][2] + (eu[2] + ev[2]) / 2,
        points,
        uv: texture.uv || shape.uv,
        texture,
        light: up > 0.5 ? 1 : left ? 0.8 : 0.6,
      });
    }
  }
  faces.sort((a, b) => a.depth - b.depth);
  const unit = SIZE / 16;
  const screen = (v: Vec) => [(8 + v[0]) * unit, (8 - v[1]) * unit];
  for (const face of faces) {
    const img = await image(face.texture.texture);
    if (!img) continue;
    let [o, u, v] = face.points.map(screen);
    let [u0, v0, u1, v1] = face.uv;
    const far = () => [u[0] + v[0] - o[0], u[1] + v[1] - o[1]];
    // Canvas source rectangles cannot be mirrored, so a flipped UV moves the origin corner instead.
    if (u0 > u1) {
      [u0, u1] = [u1, u0];
      [o, u, v] = [u, o, far()];
    }
    if (v0 > v1) {
      [v0, v1] = [v1, v0];
      [o, u, v] = [v, far(), o];
    }
    const tex = paint(img, [u0, v0, u1, v1], face.texture.tint, face.light);
    g.setTransform(
      (u[0] - o[0]) / tex.width,
      (u[1] - o[1]) / tex.width,
      (v[0] - o[0]) / tex.height,
      (v[1] - o[1]) / tex.height,
      o[0],
      o[1],
    );
    g.drawImage(tex, 0, 0);
  }
  return canvas.toDataURL();
}

function iconImage(material: string) {
  let url = rendered.get(material);
  if (!url) {
    url = iconFor(material).then((icon) =>
      icon ? draw(icon).catch(() => null) : null,
    );
    rendered.set(material, url);
    url.then((v) => {
      if (!v) rendered.delete(material);
    });
  }
  return url;
}

/** English item name from the client's language file, once known. */
export function useItemLabel(material: string) {
  const [label, setLabel] = useState("");
  useEffect(() => {
    let live = true;
    setLabel("");
    if (material && material !== "minecraft:air")
      iconFor(material).then((i) => live && setLabel(i?.label || ""));
    return () => {
      live = false;
    };
  }, [material]);
  return label;
}

export function useItemImage(material: string) {
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => {
    let live = true;
    setUrl(null);
    if (material && material !== "minecraft:air")
      iconImage(material).then((v) => live && setUrl(v));
    return () => {
      live = false;
    };
  }, [material]);
  return url;
}

export function ItemIcon({
  material,
  glint,
}: {
  material: string;
  glint?: boolean;
}) {
  const url = useItemImage(material);
  if (!url)
    return (
      <span className="mc-icon-fallback" aria-hidden="true">
        {material
          .replace("minecraft:", "")
          .split("_")
          .map((w) => w[0])
          .join("")
          .slice(0, 3)}
      </span>
    );
  return (
    <span className="mc-icon" aria-hidden="true">
      <img src={url} alt="" draggable={false} />
      {glint && (
        <span
          className="mc-glint"
          style={{ maskImage: `url(${url})`, WebkitMaskImage: `url(${url})` }}
        />
      )}
    </span>
  );
}

/** A GUI sprite such as an empty armour slot outline, when the client provides one. */
export function Sprite({ texture }: { texture: string }) {
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => {
    let live = true;
    setUrl(null);
    textures().then(async (ok) => {
      const img = ok ? await image(texture) : null;
      if (live) setUrl(img ? img.src : null);
    });
    return () => {
      live = false;
    };
  }, [texture]);
  return url ? (
    <img
      className="mc-sprite"
      src={url}
      alt=""
      aria-hidden="true"
      draggable={false}
    />
  ) : null;
}

// Minecraft's default ASCII bitmap font: a 16×16 grid of 8×8 glyphs whose width is the last opaque column.
let font:
  | Promise<{ img: HTMLImageElement; widths: number[] } | null>
  | undefined;
function loadFont() {
  font ??= textures()
    .then((ok) => (ok ? image("font/ascii") : null))
    .then((img) => {
      if (!img) {
        font = undefined;
        return null;
      }
      const c = document.createElement("canvas");
      c.width = img.width;
      c.height = img.height;
      const g = c.getContext("2d", { willReadFrequently: true })!;
      g.drawImage(img, 0, 0);
      const cell = img.width / 16;
      const data = g.getImageData(0, 0, c.width, c.height).data;
      const widths = Array.from({ length: 256 }, (_, ch) => {
        if (ch === 32) return 4;
        const cx = (ch % 16) * cell,
          cy = Math.floor(ch / 16) * cell;
        for (let x = cell - 1; x >= 0; x--)
          for (let y = 0; y < cell; y++)
            if (data[((cy + y) * c.width + cx + x) * 4 + 3] > 0)
              return Math.round(((x + 2) * 8) / cell);
        return 0;
      });
      return { img, widths };
    });
  return font;
}

export function McText({
  text,
  color = "#ffffff",
  shadow = true,
  className = "",
}: {
  text: string;
  color?: string;
  shadow?: boolean;
  className?: string;
}) {
  const [url, setUrl] = useState<{ src: string; width: number } | null>(null);
  const ascii = /^[\x20-\x7e]*$/.test(text);
  useEffect(() => {
    let live = true;
    setUrl(null);
    if (!ascii || !text) return;
    loadFont().then((f) => {
      if (!f || !live) return;
      const cell = f.img.width / 16,
        k = cell / 8;
      const width =
        [...text].reduce((w, ch) => w + f.widths[ch.charCodeAt(0)], 0) +
        (shadow ? 1 : 0);
      const c = document.createElement("canvas");
      c.width = Math.max(1, width * k);
      c.height = 9 * k;
      const g = c.getContext("2d")!;
      g.imageSmoothingEnabled = false;
      const pass = (fill: string, offset: number) => {
        const layer = document.createElement("canvas");
        layer.width = c.width;
        layer.height = c.height;
        const l = layer.getContext("2d")!;
        let x = 0;
        for (const ch of text) {
          const code = ch.charCodeAt(0);
          l.drawImage(
            f.img,
            (code % 16) * cell,
            Math.floor(code / 16) * cell,
            cell,
            cell,
            x * k,
            0,
            cell,
            cell,
          );
          x += f.widths[code];
        }
        l.globalCompositeOperation = "source-in";
        l.fillStyle = fill;
        l.fillRect(0, 0, layer.width, layer.height);
        g.drawImage(layer, offset * k, offset * k);
      };
      if (shadow) {
        const rgb = parseInt(color.slice(1), 16);
        pass(
          `rgb(${((rgb >> 16) & 255) >> 2},${((rgb >> 8) & 255) >> 2},${(rgb & 255) >> 2})`,
          1,
        );
      }
      pass(color, 0);
      setUrl({ src: c.toDataURL(), width });
    });
    return () => {
      live = false;
    };
  }, [text, color, shadow, ascii]);
  if (!url)
    return (
      <span className={`mc-text-fallback ${className}`} style={{ color }}>
        {text}
      </span>
    );
  return (
    <img
      className={`mc-text ${className}`}
      src={url.src}
      alt={text}
      draggable={false}
      style={{
        width: `calc(var(--px) * ${url.width})`,
        height: "calc(var(--px) * 9)",
      }}
    />
  );
}
