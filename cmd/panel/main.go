package main

import (
	"context"
	"fmt"
	"github.com/garder500/minecraft-bungee/internal/control"
	"github.com/garder500/minecraft-bungee/internal/panel"
	"github.com/garder500/minecraft-bungee/internal/players"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
)

func main() {
	if len(os.Args) > 1 && os.Args[1] == "health" {
		r, e := http.Get("http://127.0.0.1:8080/healthz")
		if e != nil {
			os.Exit(1)
		}
		r.Body.Close()
		if r.StatusCode != 200 {
			os.Exit(1)
		}
		return
	}
	db, e := panel.Open(panel.DBPath())
	if e != nil {
		log.Fatal(e)
	}
	defer db.Close()
	if len(os.Args) > 1 && os.Args[1] == "admin" {
		if len(os.Args) != 3 {
			log.Fatal("usage: panel admin USERNAME < password-file")
		}
		b, e := io.ReadAll(io.LimitReader(os.Stdin, 1024))
		if e != nil {
			log.Fatal(e)
		}
		if e = panel.Bootstrap(db, os.Args[2], strings.TrimRight(string(b), "\r\n")); e != nil {
			log.Fatal(e)
		}
		fmt.Println("Administrator created")
		return
	}
	token, e := control.Secret("/run/secrets/controller_token")
	if e != nil {
		log.Fatal(e)
	}
	origin := os.Getenv("PUBLIC_ORIGIN")
	if origin == "" {
		log.Fatal("PUBLIC_ORIGIN required")
	}
	controller := os.Getenv("CONTROLLER_URL")
	if controller == "" {
		controller = "http://controller:8081"
	}
	app := panel.New(db, origin, controller, token)
	app.PackFile = os.Getenv("MENU_PACK_FILE")
	app.Textures = &panel.TextureStore{Version: os.Getenv("MC_VERSION"), EULA: strings.EqualFold(os.Getenv("EULA"), "true"), CacheDir: filepath.Join(filepath.Dir(panel.DBPath()), "textures")}
	go app.Textures.Ready(context.Background())
	log.Print("panel listening on :8080")
	bridgeToken, err := control.Secret("/run/secrets/role_bridge_token")
	if err != nil {
		log.Fatal(err)
	}
	directory := &players.Service{DB: db}
	if err := control.ListenAll(map[string]http.Handler{":8080": app.Handler(), ":8083": directory.BridgeHandler(bridgeToken)}); err != nil {
		log.Fatal(err)
	}
}
