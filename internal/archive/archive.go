// Package archive inspects and stages existing Paper servers without starting Java.
package archive

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
)

const MaxExpanded = int64(40 << 30)
const MaxFiles = 200000

var versionRE = regexp.MustCompile(`\b(1\.[0-9]+(?:\.[0-9]+)?|26\.[0-9]+(?:\.[0-9]+)?)\b`)

type Report struct {
	Root        string   `json:"root"`
	Version     string   `json:"version"`
	Java        int      `json:"java"`
	ModernPaper bool     `json:"modernPaper"`
	Files       int      `json:"files"`
	Bytes       uint64   `json:"expandedBytes"`
	Worlds      []string `json:"worlds"`
	Plugins     []string `json:"plugins"`
	Compatible  bool     `json:"compatible"`
	Problems    []string `json:"problems"`
}

func safe(f *zip.File) error {
	n := f.Name
	if strings.ContainsAny(n, "\\\x00:") || strings.HasPrefix(n, "/") || path.Clean(n) != strings.TrimSuffix(n, "/") || n == "." || n == ".." || strings.HasPrefix(n, "../") {
		return fmt.Errorf("unsafe ZIP path: %q", n)
	}
	if f.Mode()&os.ModeSymlink != 0 || (!f.FileInfo().IsDir() && !f.Mode().IsRegular()) {
		return fmt.Errorf("unsupported ZIP entry: %q", n)
	}
	return nil
}
func read(f *zip.File, max int64) ([]byte, error) {
	if f.UncompressedSize64 > uint64(max) {
		return nil, errors.New("metadata exceeds size limit")
	}
	r, e := f.Open()
	if e != nil {
		return nil, e
	}
	defer r.Close()
	return io.ReadAll(io.LimitReader(r, max+1))
}
func Inspect(filename string) (Report, error) {
	z, err := zip.OpenReader(filename)
	if err != nil {
		return Report{}, err
	}
	defer z.Close()
	return inspectReader(z)
}
func inspectReader(z *zip.ReadCloser) (Report, error) {
	var out Report
	var e error
	if len(z.File) > MaxFiles {
		return out, errors.New("too many archive entries")
	}
	out.Files = len(z.File)
	seen := map[string]bool{}
	roots := []string{}
	for _, f := range z.File {
		if e = safe(f); e != nil {
			return out, e
		}
		key := strings.TrimSuffix(f.Name, "/")
		if seen[key] {
			return out, fmt.Errorf("duplicate ZIP path: %s", key)
		}
		seen[key] = true
		if f.UncompressedSize64 > uint64(MaxExpanded)-out.Bytes {
			return out, errors.New("archive expansion limit exceeded")
		}
		out.Bytes += f.UncompressedSize64
		if path.Base(f.Name) == "server.properties" {
			roots = append(roots, strings.TrimSuffix(f.Name, "server.properties"))
		}
	}
	if len(roots) != 1 {
		return out, errors.New("archive must contain exactly one server.properties")
	}
	out.Root = roots[0]
	versions := map[string]bool{}
	for _, f := range z.File {
		if !strings.HasPrefix(f.Name, out.Root) {
			if !f.FileInfo().IsDir() {
				return out, errors.New("files outside server root; repack the archive")
			}
			continue
		}
		n := strings.TrimPrefix(f.Name, out.Root)
		if n == "config/paper-global.yml" {
			out.ModernPaper = true
		}
		if path.Base(n) == "level.dat" {
			out.Worlds = append(out.Worlds, path.Dir(n))
		}
		if path.Dir(n) == "plugins" && strings.HasSuffix(n, ".jar") {
			out.Plugins = append(out.Plugins, n)
		}
		if n == "version_history.json" || n == "version.json" {
			b, e := read(f, 1<<20)
			if e != nil {
				return out, e
			}
			var v map[string]any
			if json.Unmarshal(b, &v) == nil {
				for _, key := range []string{"currentVersion", "id", "name"} {
					if s, ok := v[key].(string); ok {
						if ver := versionRE.FindString(s); ver != "" {
							versions[ver] = true
						}
					}
				}
			}
		}
		// Paper launcher names and cached version directories provide an additional version check.
		if strings.HasSuffix(n, ".jar") && strings.HasPrefix(strings.ToLower(path.Base(n)), "paper-") && (path.Dir(n) == "." || strings.HasPrefix(n, "versions/") || strings.HasPrefix(n, "cache/")) {
			if ver := versionRE.FindString(path.Base(n)); ver != "" {
				versions[ver] = true
			}
		}
		if (n == "server.jar" || n == "paper.jar") && f.UncompressedSize64 < 256<<20 {
			b, e := read(f, 256<<20)
			if e != nil {
				return out, e
			}
			inner, e := zip.NewReader(bytes.NewReader(b), int64(len(b)))
			if e == nil {
				for _, j := range inner.File {
					if j.Name == "version.json" {
						v, e := read(j, 1<<20)
						if e != nil {
							return out, e
						}
						var meta struct {
							ID string `json:"id"`
						}
						if json.Unmarshal(v, &meta) == nil {
							if ver := versionRE.FindString(meta.ID); ver != "" {
								versions[ver] = true
							}
						}
					}
				}
			}
		}
	}
	if len(versions) == 1 {
		for v := range versions {
			out.Version = v
		}
	} else {
		out.Problems = append(out.Problems, "Minecraft version is missing or ambiguous; inspect runtime artifacts before import")
	}
	var major, minor, patch int
	fmt.Sscanf(out.Version, "%d.%d.%d", &major, &minor, &patch)
	if major == 1 && minor >= 19 {
		out.Java = 17
		if minor >= 21 || (minor == 20 && patch >= 5) {
			out.Java = 21
		}
	} else if major >= 26 {
		out.Java = 25
	} else {
		out.Problems = append(out.Problems, "automatic import requires Paper 1.19+ with modern configuration; older servers need compatibility review")
	}
	if !out.ModernPaper {
		out.Problems = append(out.Problems, "config/paper-global.yml missing; modern Paper forwarding compatibility is unconfirmed")
	}
	if len(out.Worlds) == 0 {
		out.Problems = append(out.Problems, "no world level.dat found")
	}
	sort.Strings(out.Worlds)
	sort.Strings(out.Plugins)
	out.Compatible = len(out.Problems) == 0
	return out, nil
}
func Import(filename, dest, expected string) (Report, error) {
	z, e := zip.OpenReader(filename)
	if e != nil {
		return Report{}, e
	}
	defer z.Close()
	report, e := inspectReader(z)
	if e != nil {
		return report, e
	}
	if !report.Compatible {
		return report, errors.New("compatibility inspection failed")
	}
	if expected == "" || expected != report.Version {
		return report, errors.New("--version must match the inspected Minecraft version")
	}
	if _, e = os.Lstat(dest); !os.IsNotExist(e) {
		return report, errors.New("destination already exists or cannot be inspected")
	}
	parent := filepath.Dir(dest)
	if e = os.MkdirAll(parent, 0750); e != nil {
		return report, e
	}
	stage, e := os.MkdirTemp(parent, ".creative-import-")
	if e != nil {
		return report, e
	}
	defer os.RemoveAll(stage)
	var expanded int64
	for _, f := range z.File {
		if !strings.HasPrefix(f.Name, report.Root) {
			continue
		}
		n := strings.TrimPrefix(f.Name, report.Root)
		if n == "" {
			continue
		}
		target := filepath.Join(stage, filepath.FromSlash(n))
		if f.FileInfo().IsDir() {
			if e = os.MkdirAll(target, 0750); e != nil {
				return report, e
			}
			continue
		}
		if e = os.MkdirAll(filepath.Dir(target), 0750); e != nil {
			return report, e
		}
		src, e := f.Open()
		if e != nil {
			return report, e
		}
		dst, e := os.OpenFile(target, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0640)
		if e != nil {
			src.Close()
			return report, e
		}
		nbytes, copyErr := io.Copy(dst, io.LimitReader(src, MaxExpanded-expanded+1))
		expanded += nbytes
		src.Close()
		closeErr := dst.Close()
		if copyErr != nil {
			return report, copyErr
		}
		if closeErr != nil {
			return report, closeErr
		}
		if expanded > MaxExpanded {
			return report, errors.New("archive expansion limit exceeded")
		}
	}
	// Reserve destination atomically; a concurrent importer cannot replace it.
	if e = os.Mkdir(dest, 0750); e != nil {
		return report, e
	}
	entries, e := os.ReadDir(stage)
	if e != nil {
		return report, e
	}
	for _, entry := range entries {
		if e = os.Rename(filepath.Join(stage, entry.Name()), filepath.Join(dest, entry.Name())); e != nil {
			return report, fmt.Errorf("incomplete import retained at %s: %w", dest, e)
		}
	}
	return report, nil
}
