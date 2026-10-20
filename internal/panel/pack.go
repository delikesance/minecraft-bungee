package panel

import (
	"bytes"
	"crypto/sha1"
	"encoding/hex"
	"net/http"
	"os"
	"sync"
	"time"
)

const maxPackBytes = 32 << 20

type packCache struct {
	once   sync.Once
	data   []byte
	digest string
}

// load reads the pack once; the SHA-1 in the URL is what Minecraft clients verify and cache by.
func (s *Server) loadPack() ([]byte, string) {
	s.pack.once.Do(func() {
		if s.PackFile == "" {
			return
		}
		info, e := os.Stat(s.PackFile)
		if e != nil || !info.Mode().IsRegular() || info.Size() > maxPackBytes {
			return
		}
		b, e := os.ReadFile(s.PackFile)
		if e != nil {
			return
		}
		sum := sha1.Sum(b)
		s.pack.data, s.pack.digest = b, hex.EncodeToString(sum[:])
	})
	return s.pack.data, s.pack.digest
}

// servePack is public and unauthenticated: the Minecraft client downloads it directly.
// Only the exact content hash is served so a stale announcement can never fetch the wrong pack.
func (s *Server) servePack(w http.ResponseWriter, r *http.Request) {
	data, digest := s.loadPack()
	if data == nil || r.PathValue("file") != digest+".zip" {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
	w.Header().Set("ETag", `"`+digest+`"`)
	http.ServeContent(w, r, "vampyuyu-menus.zip", time.Time{}, bytes.NewReader(data))
}
