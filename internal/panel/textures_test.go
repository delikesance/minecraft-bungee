package panel

import (
	"archive/zip"
	"bytes"
	"context"
	"crypto/sha1"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func fakeClientJar(t *testing.T) []byte {
	t.Helper()
	files := map[string]string{
		"assets/minecraft/items/iron_sword.json":                     `{"model":{"type":"minecraft:model","model":"minecraft:item/iron_sword"}}`,
		"assets/minecraft/models/item/iron_sword.json":               `{"parent":"minecraft:item/handheld","textures":{"layer0":"minecraft:item/iron_sword"}}`,
		"assets/minecraft/models/item/handheld.json":                 `{"parent":"item/generated"}`,
		"assets/minecraft/models/item/generated.json":                `{"parent":"builtin/generated"}`,
		"assets/minecraft/items/leather_helmet.json":                 `{"model":{"type":"minecraft:model","model":"minecraft:item/leather_helmet","tints":[{"type":"minecraft:dye","default":-6265536}]}}`,
		"assets/minecraft/models/item/leather_helmet.json":           `{"parent":"item/generated","textures":{"layer0":"item/leather_helmet","layer1":"item/leather_helmet_overlay"}}`,
		"assets/minecraft/items/spyglass.json":                       `{"model":{"type":"minecraft:select","property":"minecraft:display_context","cases":[{"when":["gui","ground"],"model":{"type":"minecraft:model","model":"minecraft:item/spyglass"}}],"fallback":{"type":"minecraft:model","model":"minecraft:item/spyglass_in_hand"}}}`,
		"assets/minecraft/models/item/spyglass.json":                 `{"parent":"item/generated","textures":{"layer0":"item/spyglass"}}`,
		"assets/minecraft/items/stone.json":                          `{"model":{"type":"minecraft:model","model":"minecraft:block/stone"}}`,
		"assets/minecraft/models/block/stone.json":                   `{"parent":"minecraft:block/cube_all","textures":{"all":"minecraft:block/stone"}}`,
		"assets/minecraft/models/block/cube_all.json":                `{"parent":"block/cube","textures":{"particle":"#all","up":"#all","north":"#all","east":"#all"}}`,
		"assets/minecraft/models/block/cube.json":                    `{"parent":"block/block","elements":[{"from":[0,0,0],"to":[16,16,16],"faces":{"up":{"texture":"#up"},"north":{"texture":"#north"},"east":{"texture":"#east"}}}]}`,
		"assets/minecraft/models/block/block.json":                   `{"display":{"gui":{"rotation":[30,225,0],"translation":[0,0,0],"scale":[0.625,0.625,0.625]}}}`,
		"assets/minecraft/items/shield.json":                         `{"model":{"type":"minecraft:condition","property":"minecraft:using_item","on_false":{"type":"minecraft:special","base":"minecraft:item/shield","model":{"type":"minecraft:shield"}},"on_true":{"type":"minecraft:special","base":"minecraft:item/shield_blocking","model":{"type":"minecraft:shield"}}}}`,
		"assets/minecraft/textures/entity/shield_base_nopattern.png": "png",
		"assets/minecraft/lang/en_us.json":                           `{"item.minecraft.iron_sword":"Iron Sword","block.minecraft.stone":"Stone"}`,
		"assets/minecraft/textures/item/iron_sword.png":              "png",
		"assets/minecraft/textures/item/leather_helmet.png":          "png",
		"assets/minecraft/textures/item/leather_helmet_overlay.png":  "png",
		"assets/minecraft/textures/item/spyglass.png":                "png",
		"assets/minecraft/textures/block/stone.png":                  "png",
		"assets/minecraft/textures/entity/creeper.png":               "not kept",
		"net/minecraft/client/Main.class":                            "code",
	}
	var b bytes.Buffer
	w := zip.NewWriter(&b)
	for name, body := range files {
		f, _ := w.Create(name)
		f.Write([]byte(body))
	}
	w.Close()
	return b.Bytes()
}

func fakeMojang(t *testing.T, jar []byte) (*httptest.Server, *int) {
	sum := sha1.Sum(jar)
	downloads := 0
	var srv *httptest.Server
	srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/manifest.json":
			json.NewEncoder(w).Encode(map[string]any{"versions": []map[string]string{{"id": "26.3", "url": srv.URL + "/26.3.json"}}})
		case "/26.3.json":
			json.NewEncoder(w).Encode(map[string]any{"downloads": map[string]any{"client": map[string]string{"sha1": hex.EncodeToString(sum[:]), "url": srv.URL + "/client.jar"}}})
		case "/client.jar":
			downloads++
			w.Write(jar)
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(srv.Close)
	return srv, &downloads
}

func TestTexturesNeedEULAAndVersion(t *testing.T) {
	s, _ := setup(t)
	c, _ := login(t, s)
	if w := request(t, s, "GET", "/api/v1/textures/icons?m=minecraft:stone", "", c, ""); w.Code != 503 {
		t.Fatal("textures without a store", w.Code)
	}
	if w := request(t, s, "GET", "/api/v1/textures/26.3/item/stone.png", "", c, ""); w.Code != 404 {
		t.Fatal("texture without a store", w.Code)
	}
	s.Textures = &TextureStore{Version: "26.3", CacheDir: t.TempDir()}
	if w := request(t, s, "GET", "/api/v1/textures/icons?m=minecraft:stone", "", c, ""); w.Code != 503 || !strings.Contains(w.Body.String(), "EULA") {
		t.Fatal("textures before the EULA", w.Code, w.Body.String())
	}
}

func TestTexturesDownloadResolveAndServe(t *testing.T) {
	jar := fakeClientJar(t)
	mojang, downloads := fakeMojang(t, jar)
	cache := t.TempDir()
	s, _ := setup(t)
	s.Textures = &TextureStore{Version: "26.3", EULA: true, CacheDir: cache, Manifest: mojang.URL + "/manifest.json"}
	if w := request(t, s, "GET", "/api/v1/textures/icons?m=minecraft:stone", "", nil, ""); w.Code != 401 {
		t.Fatal("anonymous icons", w.Code)
	}
	c, _ := login(t, s)
	w := request(t, s, "GET", "/api/v1/textures/icons?m=minecraft:iron_sword,minecraft:leather_helmet,minecraft:spyglass,minecraft:shield,minecraft:stone,minecraft:unknown,../etc", "", c, "")
	if w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	var out struct {
		Base  string          `json:"base"`
		Icons map[string]Icon `json:"icons"`
	}
	json.Unmarshal(w.Body.Bytes(), &out)
	if out.Base != "/api/v1/textures/26.3/" || len(out.Icons) != 5 {
		t.Fatalf("icons %+v", out)
	}
	sword := out.Icons["minecraft:iron_sword"]
	if sword.Label != "Iron Sword" || len(sword.Layers) != 1 || sword.Layers[0].Texture != "item/iron_sword" || sword.GUI != nil {
		t.Fatalf("sword %+v", sword)
	}
	helmet := out.Icons["minecraft:leather_helmet"]
	if len(helmet.Layers) != 2 || helmet.Layers[0].Tint != "#a06540" || helmet.Layers[1].Tint != "" {
		t.Fatalf("helmet tint %+v", helmet)
	}
	if spy := out.Icons["minecraft:spyglass"]; len(spy.Layers) != 1 || spy.Layers[0].Texture != "item/spyglass" {
		t.Fatalf("spyglass should use its GUI model %+v", spy)
	}
	if shield := out.Icons["minecraft:shield"]; len(shield.Layers) != 1 || shield.Layers[0].Texture != "entity/shield_base_nopattern" || shield.Layers[0].UV == nil {
		t.Fatalf("shield %+v", shield)
	}
	stone := out.Icons["minecraft:stone"]
	if stone.Label != "Stone" || len(stone.Elements) != 1 || stone.GUI == nil || stone.GUI.Rotation != [3]float64{30, 225, 0} || stone.Elements[0].Faces["north"].Texture != "block/stone" {
		t.Fatalf("stone %+v", stone)
	}
	if w = request(t, s, "GET", "/api/v1/textures/26.3/block/stone.png", "", c, ""); w.Code != 200 || w.Header().Get("Content-Type") != "image/png" || !strings.Contains(w.Header().Get("Cache-Control"), "private") {
		t.Fatal("texture", w.Code, w.Header())
	}
	for _, p := range []string{"/api/v1/textures/26.2/block/stone.png", "/api/v1/textures/26.3/entity/creeper.png", "/api/v1/textures/26.3/../lang/en_us.json", "/api/v1/textures/26.3/block/./stone.png"} {
		if w = request(t, s, "GET", p, "", c, ""); w.Code == 200 {
			t.Fatal("served", p)
		}
	}
	cached, e := os.ReadFile(filepath.Join(cache, "26.3.zip"))
	if e != nil || len(cached) >= len(jar) || bytes.Contains(cached, []byte("Main.class")) {
		t.Fatal("cache should only keep textures", e, len(cached), len(jar))
	}
	again := &TextureStore{Version: "26.3", EULA: true, CacheDir: cache, Manifest: mojang.URL + "/manifest.json"}
	if _, e = again.Ready(context.Background()); e != nil || *downloads != 1 {
		t.Fatal("cache not reused", e, *downloads)
	}
}

func TestTexturesRejectTamperedJar(t *testing.T) {
	jar := fakeClientJar(t)
	mojang, _ := fakeMojang(t, jar)
	jar[len(jar)-1] ^= 1
	store := &TextureStore{Version: "26.3", EULA: true, CacheDir: t.TempDir(), Manifest: mojang.URL + "/manifest.json"}
	if _, e := store.Ready(context.Background()); e == nil || !strings.Contains(e.Error(), "checksum") {
		t.Fatal("tampered jar accepted", e)
	}
	missing := &TextureStore{Version: "1.0-unknown", EULA: true, CacheDir: t.TempDir(), Manifest: mojang.URL + "/manifest.json"}
	if _, e := missing.Ready(context.Background()); e == nil {
		t.Fatal("unknown version accepted")
	}
}
