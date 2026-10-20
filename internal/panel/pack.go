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
	mu     sync.Mutex
	data   []byte
	digest string
}

// loadPack reads the pack on first success; a missing file is retried so the panel can start before the ZIP exists.
func (s *Server) loadPack() ([]byte, string) {
	s.pack.mu.Lock()
	defer s.pack.mu.Unlock()
	if s.pack.data != nil || s.PackFile == "" {
		return s.pack.data, s.pack.digest
	}
	info, e := os.Stat(s.PackFile)
	if e != nil || !info.Mode().IsRegular() || info.Size() > maxPackBytes {
		return nil, ""
	}
	b, e := os.ReadFile(s.PackFile)
	if e != nil {
		return nil, ""
	}
	sum := sha1.Sum(b)
	s.pack.data, s.pack.digest = b, hex.EncodeToString(sum[:])
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
