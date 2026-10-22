package panel

import (
	"archive/zip"
	"bytes"
	"context"
	"crypto/sha1"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/garder500/minecraft-bungee/internal/control"
)

// Item textures are Mojang's assets, so they are never committed, baked into an image or fetched from a
// third-party mirror. Once the operator accepts the Minecraft EULA, the panel downloads the official client
// jar for MC_VERSION from Mojang, keeps only the textures, models and English names it needs in its private
// state directory, and serves them to signed-in administrators.
const (
	mojangManifest  = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
	maxClientJar    = 160 << 20
	maxTextureEntry = 4 << 20
	textureRetry    = 5 * time.Minute
)

var (
	validVersion  = regexp.MustCompile(`^[0-9A-Za-z][0-9A-Za-z._-]{0,40}$`)
	validMaterial = regexp.MustCompile(`^minecraft:[a-z0-9_./-]{1,96}$`)
	textureKeep   = []string{"assets/minecraft/textures/item/", "assets/minecraft/textures/block/", "assets/minecraft/textures/gui/sprites/container/slot/", "assets/minecraft/textures/font/ascii.png", "assets/minecraft/textures/entity/shield_base_nopattern.png", "assets/minecraft/textures/entity/shield/base_nopattern.png", "assets/minecraft/models/item/", "assets/minecraft/models/block/", "assets/minecraft/items/", "assets/minecraft/lang/en_us.json"}
)

type TextureStore struct {
	Version, CacheDir, Manifest string
	EULA                        bool
	HTTP                        *http.Client
	mu                          sync.Mutex
	set                         *textureSet
	loading                     chan struct{}
	err                         error
	failed                      time.Time
}

type textureSet struct {
	files  map[string][]byte
	labels map[string]string
	icons  sync.Map
}

// Ready returns the extracted assets, downloading them on first use; concurrent callers share one download.
func (t *TextureStore) Ready(ctx context.Context) (*textureSet, error) {
	if t == nil || !t.EULA {
		return nil, errors.New("item textures need EULA=TRUE on the panel")
	}
	if !validVersion.MatchString(t.Version) {
		return nil, errors.New("item textures need MC_VERSION on the panel")
	}
	t.mu.Lock()
	if t.set != nil {
		defer t.mu.Unlock()
		return t.set, nil
	}
	if t.loading == nil {
		if t.err != nil && time.Since(t.failed) < textureRetry {
			defer t.mu.Unlock()
			return nil, t.err
		}
		t.loading = make(chan struct{})
		go t.load(t.loading)
	}
	done := t.loading
	t.mu.Unlock()
	select {
	case <-ctx.Done():
		return nil, errors.New("item textures are still downloading")
	case <-done:
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.set, t.err
}

func (t *TextureStore) load(done chan struct{}) {
	set, e := t.open()
	t.mu.Lock()
	t.set, t.err, t.loading = set, e, nil
	if e != nil {
		t.failed = time.Now()
		slog.Error("item textures unavailable", "version", t.Version, "error", e)
	}
	t.mu.Unlock()
	close(done)
}

func (t *TextureStore) open() (*textureSet, error) {
	cache := filepath.Join(t.CacheDir, t.Version+".zip")
	if b, e := os.ReadFile(cache); e == nil {
		if set, e := readTextureSet(b); e == nil {
			return set, nil
		}
	}
	b, e := t.download()
	if e != nil {
		return nil, e
	}
	set, e := readTextureSet(b)
	if e != nil {
		return nil, e
	}
	if e = os.MkdirAll(t.CacheDir, 0o700); e == nil {
		tmp := cache + ".tmp"
		if e = os.WriteFile(tmp, b, 0o600); e == nil {
			e = os.Rename(tmp, cache)
		}
	}
	if e != nil {
		slog.Warn("item texture cache not written", "error", e)
	}
	return set, nil
}

func (t *TextureStore) get(url string, limit int64) ([]byte, error) {
	client := t.HTTP
	if client == nil {
		client = &http.Client{Timeout: 3 * time.Minute}
	}
	res, e := client.Get(url)
	if e != nil {
		return nil, e
	}
	defer res.Body.Close()
	if res.StatusCode != 200 {
		return nil, fmt.Errorf("%s returned %d", url, res.StatusCode)
	}
	b, e := io.ReadAll(io.LimitReader(res.Body, limit+1))
	if e == nil && int64(len(b)) > limit {
		e = fmt.Errorf("%s is larger than expected", url)
	}
	return b, e
}

// download fetches the client jar named by Mojang's version manifest, checks its SHA-1 and returns a small
// ZIP holding only the entries the inventory view uses.
func (t *TextureStore) download() ([]byte, error) {
	manifest := t.Manifest
	if manifest == "" {
		manifest = mojangManifest
	}
	var list struct {
		Versions []struct{ ID, URL string } `json:"versions"`
	}
	b, e := t.get(manifest, 8<<20)
	if e == nil {
		e = json.Unmarshal(b, &list)
	}
	if e != nil {
		return nil, fmt.Errorf("version manifest: %w", e)
	}
	meta := ""
	for _, v := range list.Versions {
		if v.ID == t.Version {
			meta = v.URL
		}
	}
	if meta == "" {
		return nil, fmt.Errorf("Minecraft %s is not in Mojang's version manifest", t.Version)
	}
	var version struct {
		Downloads struct {
			Client struct{ SHA1, URL string } `json:"client"`
		} `json:"downloads"`
	}
	if b, e = t.get(meta, 8<<20); e == nil {
		e = json.Unmarshal(b, &version)
	}
	if e != nil || version.Downloads.Client.URL == "" {
		return nil, fmt.Errorf("version metadata: %v", e)
	}
	jar, e := t.get(version.Downloads.Client.URL, maxClientJar)
	if e != nil {
		return nil, fmt.Errorf("client jar: %w", e)
	}
	sum := sha1.Sum(jar)
	if hex.EncodeToString(sum[:]) != strings.ToLower(version.Downloads.Client.SHA1) {
		return nil, errors.New("client jar checksum mismatch")
	}
	in, e := zip.NewReader(bytes.NewReader(jar), int64(len(jar)))
	if e != nil {
		return nil, fmt.Errorf("client jar: %w", e)
	}
	var out bytes.Buffer
	w := zip.NewWriter(&out)
	for _, f := range in.File {
		if !keepTexture(f.Name) || f.UncompressedSize64 > maxTextureEntry {
			continue
		}
		r, e := f.Open()
		if e != nil {
			return nil, e
		}
		dst, e := w.Create(f.Name)
		if e == nil {
			_, e = io.Copy(dst, io.LimitReader(r, maxTextureEntry))
		}
		r.Close()
		if e != nil {
			return nil, e
		}
	}
	if e = w.Close(); e != nil {
		return nil, e
	}
	return out.Bytes(), nil
}

func keepTexture(name string) bool {
	if strings.Contains(name, "..") || strings.HasSuffix(name, "/") {
		return false
	}
	for _, p := range textureKeep {
		if strings.HasPrefix(name, p) {
			return strings.HasSuffix(name, ".png") || strings.HasSuffix(name, ".json")
		}
	}
	return false
}

func readTextureSet(b []byte) (*textureSet, error) {
	in, e := zip.NewReader(bytes.NewReader(b), int64(len(b)))
	if e != nil {
		return nil, e
	}
	set := &textureSet{files: map[string][]byte{}, labels: map[string]string{}}
	for _, f := range in.File {
		if !keepTexture(f.Name) || f.UncompressedSize64 > maxTextureEntry {
			continue
		}
		r, e := f.Open()
		if e != nil {
			return nil, e
		}
		data, e := io.ReadAll(io.LimitReader(r, maxTextureEntry))
		r.Close()
		if e != nil {
			return nil, e
		}
		set.files[strings.TrimPrefix(f.Name, "assets/minecraft/")] = data
	}
	if len(set.files) == 0 {
		return nil, errors.New("no item textures in client jar")
	}
	json.Unmarshal(set.files["lang/en_us.json"], &set.labels)
	return set, nil
}

// Icon is what the browser needs to draw one item like the inventory screen does: either flat sprite layers
// (generated item models) or cuboid elements seen through the model's GUI display transform.
type Icon struct {
	Label    string        `json:"label,omitempty"`
	Layers   []IconTexture `json:"layers,omitempty"`
	Elements []IconElement `json:"elements,omitempty"`
	GUI      *IconDisplay  `json:"gui,omitempty"`
}
type IconTexture struct {
	Texture string      `json:"texture"`
	Tint    string      `json:"tint,omitempty"`
	UV      *[4]float64 `json:"uv,omitempty"`
}
type IconElement struct {
	From     [3]float64             `json:"from"`
	To       [3]float64             `json:"to"`
	Rotation *IconRotation          `json:"rotation,omitempty"`
	Faces    map[string]IconTexture `json:"faces"`
}
type IconRotation struct {
	Origin [3]float64 `json:"origin"`
	Axis   string     `json:"axis"`
	Angle  float64    `json:"angle"`
}
type IconDisplay struct {
	Rotation    [3]float64 `json:"rotation"`
	Translation [3]float64 `json:"translation"`
	Scale       [3]float64 `json:"scale"`
}

type rawModel struct {
	Parent   string            `json:"parent"`
	Textures map[string]string `json:"textures"`
	Elements []struct {
		From     [3]float64    `json:"from"`
		To       [3]float64    `json:"to"`
		Rotation *IconRotation `json:"rotation"`
		Faces    map[string]struct {
			Texture   string      `json:"texture"`
			UV        *[4]float64 `json:"uv"`
			TintIndex *int        `json:"tintindex"`
		} `json:"faces"`
	} `json:"elements"`
	Display map[string]*struct {
		Rotation    *[3]float64 `json:"rotation"`
		Translation *[3]float64 `json:"translation"`
		Scale       *[3]float64 `json:"scale"`
	} `json:"display"`
}

func (set *textureSet) icon(material string) (Icon, bool) {
	if v, ok := set.icons.Load(material); ok {
		i, ok := v.(Icon)
		return i, ok
	}
	i, ok := set.resolve(strings.TrimPrefix(material, "minecraft:"))
	if ok {
		set.icons.Store(material, i)
	} else {
		set.icons.Store(material, false)
	}
	return i, ok
}

func (set *textureSet) resolve(name string) (Icon, bool) {
	icon := Icon{Label: set.labels["item.minecraft."+name]}
	if icon.Label == "" {
		icon.Label = set.labels["block.minecraft."+name]
	}
	model, tints := "item/"+name, []string(nil)
	if b, ok := set.files["items/"+name+".json"]; ok {
		var def struct {
			Model json.RawMessage `json:"model"`
		}
		if json.Unmarshal(b, &def) != nil {
			return icon, false
		}
		var special string
		model, tints, special = itemModel(def.Model, 0)
		if special == "shield" {
			// The shield is drawn by its entity renderer; its front plate is the 12×22 area at (1,1) of a 64px texture.
			for _, t := range []string{"entity/shield_base_nopattern", "entity/shield/base_nopattern"} {
				if set.files["textures/"+t+".png"] != nil {
					icon.Layers = []IconTexture{{Texture: t, UV: &[4]float64{0.25, 0.25, 3.25, 5.75}}}
					return icon, true
				}
			}
		}
		if model == "" {
			return icon, false
		}
	}
	textures, chain := map[string]string{}, []rawModel{}
	generated := false
	for depth, ref := 0, model; ref != "" && depth < 16; depth++ {
		ref = strings.TrimPrefix(ref, "minecraft:")
		if ref == "builtin/generated" {
			generated = true
			break
		}
		var m rawModel
		b, ok := set.files["models/"+ref+".json"]
		if !ok || json.Unmarshal(b, &m) != nil {
			break
		}
		chain = append(chain, m)
		for k, v := range m.Textures {
			if _, seen := textures[k]; !seen {
				textures[k] = v
			}
		}
		ref = m.Parent
	}
	if len(chain) == 0 {
		return icon, false
	}
	lookup := func(ref string) string {
		for i := 0; i < 8 && strings.HasPrefix(ref, "#"); i++ {
			ref = textures[ref[1:]]
		}
		ref = strings.TrimPrefix(ref, "minecraft:")
		if strings.Contains(ref, ":") || strings.Contains(ref, "..") || ref == "" || set.files["textures/"+ref+".png"] == nil {
			return ""
		}
		return ref
	}
	tint := func(index *int) string {
		if index != nil && *index >= 0 && *index < len(tints) {
			return tints[*index]
		}
		return ""
	}
	for _, m := range chain {
		if len(m.Elements) == 0 || generated {
			continue
		}
		for _, e := range m.Elements {
			out := IconElement{From: e.From, To: e.To, Rotation: e.Rotation, Faces: map[string]IconTexture{}}
			for side, f := range e.Faces {
				if t := lookup(f.Texture); t != "" {
					out.Faces[side] = IconTexture{Texture: t, UV: f.UV, Tint: tint(f.TintIndex)}
				}
			}
			if len(out.Faces) > 0 && len(icon.Elements) < 64 {
				icon.Elements = append(icon.Elements, out)
			}
		}
		break
	}
	if !generated && len(icon.Elements) > 0 {
		gui := IconDisplay{Scale: [3]float64{1, 1, 1}}
		var rotation, translation, scale bool
		for _, m := range chain {
			d := m.Display["gui"]
			if d == nil {
				continue
			}
			if d.Rotation != nil && !rotation {
				gui.Rotation, rotation = *d.Rotation, true
			}
			if d.Translation != nil && !translation {
				gui.Translation, translation = *d.Translation, true
			}
			if d.Scale != nil && !scale {
				gui.Scale, scale = *d.Scale, true
			}
		}
		icon.GUI = &gui
		return icon, true
	}
	icon.Elements = nil
	for i := 0; i < 8; i++ {
		if t := lookup(fmt.Sprintf("#layer%d", i)); t != "" {
			index := i
			icon.Layers = append(icon.Layers, IconTexture{Texture: t, Tint: tint(&index)})
		}
	}
	if len(icon.Layers) == 0 {
		// Specially rendered items (chests, shields, heads, banners) only name a particle texture.
		if t := lookup("#particle"); t != "" {
			icon.Layers = []IconTexture{{Texture: t}}
		}
	}
	return icon, len(icon.Layers) > 0
}

// itemModel picks the model an item definition shows in an inventory: the GUI case of display-context
// selections, otherwise the default branch. Special renderers also report their type.
func itemModel(raw json.RawMessage, depth int) (string, []string, string) {
	var n struct {
		Type     string            `json:"type"`
		Model    json.RawMessage   `json:"model"`
		Base     string            `json:"base"`
		Property string            `json:"property"`
		Tints    []json.RawMessage `json:"tints"`
		OnFalse  json.RawMessage   `json:"on_false"`
		Fallback json.RawMessage   `json:"fallback"`
		Models   []json.RawMessage `json:"models"`
		Cases    []struct {
			When  json.RawMessage `json:"when"`
			Model json.RawMessage `json:"model"`
		} `json:"cases"`
		Entries []struct {
			Model json.RawMessage `json:"model"`
		} `json:"entries"`
	}
	if depth > 16 || json.Unmarshal(raw, &n) != nil {
		return "", nil, ""
	}
	switch strings.TrimPrefix(n.Type, "minecraft:") {
	case "model":
		var ref string
		json.Unmarshal(n.Model, &ref)
		tints := make([]string, len(n.Tints))
		for i, t := range n.Tints {
			tints[i] = tintColor(t)
		}
		return ref, tints, ""
	case "special":
		var special struct {
			Type string `json:"type"`
		}
		json.Unmarshal(n.Model, &special)
		return n.Base, nil, strings.TrimPrefix(special.Type, "minecraft:")
	case "condition":
		return itemModel(n.OnFalse, depth+1)
	case "composite":
		if len(n.Models) > 0 {
			return itemModel(n.Models[0], depth+1)
		}
	case "select":
		if strings.TrimPrefix(n.Property, "minecraft:") == "display_context" {
			for _, c := range n.Cases {
				if strings.Contains(string(c.When), `"gui"`) {
					return itemModel(c.Model, depth+1)
				}
			}
		}
		if len(n.Fallback) > 0 {
			return itemModel(n.Fallback, depth+1)
		}
		if len(n.Cases) > 0 {
			return itemModel(n.Cases[0].Model, depth+1)
		}
	case "range_dispatch":
		if len(n.Fallback) > 0 {
			return itemModel(n.Fallback, depth+1)
		}
		if len(n.Entries) > 0 {
			return itemModel(n.Entries[0].Model, depth+1)
		}
	}
	return "", nil, ""
}

// tintColor approximates a tint source with its constant or default colour; grass uses the plains colour.
func tintColor(raw json.RawMessage) string {
	var t struct {
		Type    string `json:"type"`
		Value   *int64 `json:"value"`
		Default *int64 `json:"default"`
	}
	if json.Unmarshal(raw, &t) != nil {
		return ""
	}
	v := t.Value
	if v == nil {
		v = t.Default
	}
	if v == nil {
		switch strings.TrimPrefix(t.Type, "minecraft:") {
		case "grass":
			c := int64(0x91bd59)
			v = &c
		case "foliage", "dry_foliage":
			c := int64(0x77ab2f)
			v = &c
		default:
			return ""
		}
	}
	return fmt.Sprintf("#%06x", *v&0xffffff)
}

func (s *Server) textureRoutes(mux *http.ServeMux, protected func(func(http.ResponseWriter, *http.Request, session)) http.HandlerFunc) {
	mux.HandleFunc("GET /api/v1/textures/icons", protected(func(w http.ResponseWriter, r *http.Request, v session) {
		ctx, cancel := context.WithTimeout(r.Context(), 90*time.Second)
		defer cancel()
		set, e := s.Textures.Ready(ctx)
		if e != nil {
			fail(w, 503, e.Error())
			return
		}
		icons := map[string]Icon{}
		materials := strings.Split(r.URL.Query().Get("m"), ",")
		sort.Strings(materials)
		for i, m := range materials {
			if i >= 128 {
				break
			}
			if !validMaterial.MatchString(m) {
				continue
			}
			if icon, ok := set.icon(m); ok {
				icons[m] = icon
			}
		}
		control.JSON(w, 200, map[string]any{"base": "/api/v1/textures/" + s.Textures.Version + "/", "icons": icons})
	}))
	mux.HandleFunc("GET /api/v1/textures/{version}/{file...}", protected(func(w http.ResponseWriter, r *http.Request, v session) {
		file := r.PathValue("file")
		if s.Textures == nil || r.PathValue("version") != s.Textures.Version || path.Clean(file) != file || !strings.HasSuffix(file, ".png") {
			fail(w, 404, "texture not found")
			return
		}
		set, e := s.Textures.Ready(r.Context())
		if e != nil {
			fail(w, 503, e.Error())
			return
		}
		data, ok := set.files["textures/"+file]
		if !ok {
			fail(w, 404, "texture not found")
			return
		}
		w.Header().Set("Content-Type", "image/png")
		w.Header().Set("Cache-Control", "private, max-age=604800, immutable")
		w.Write(data)
	}))
}
