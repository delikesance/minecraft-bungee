package control

import (
	"context"
	"encoding/json"
	"golang.org/x/sys/unix"
	"io/fs"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"time"
)

type Process struct {
	PID    string `json:"pid"`
	Name   string `json:"name"`
	CPU    string `json:"cpu"`
	Memory string `json:"memory"`
}
type Metrics struct {
	ID               string    `json:"id"`
	SampledAt        string    `json:"sampledAt"`
	CPUPercent       *float64  `json:"cpuPercent"`
	MemoryBytes      *uint64   `json:"memoryBytes"`
	MemoryLimit      *uint64   `json:"memoryLimit"`
	PIDs             *uint64   `json:"pids"`
	Restarts         int       `json:"restarts"`
	StartedAt        string    `json:"startedAt"`
	StorageBytes     *uint64   `json:"storageBytes"`
	StorageSampledAt string    `json:"storageSampledAt"`
	DiskAvailable    *uint64   `json:"diskAvailable"`
	DiskTotal        *uint64   `json:"diskTotal"`
	Processes        []Process `json:"processes"`
	Warnings         []string  `json:"warnings"`
}
type storageSample struct {
	at    time.Time
	bytes uint64
	err   error
}

var storageCache = struct {
	sync.Mutex
	values map[string]storageSample
}{values: map[string]storageSample{}}

func (s *Server) metrics(ctx context.Context, id string) (Metrics, error) {
	out := Metrics{ID: id, SampledAt: time.Now().UTC().Format(time.RFC3339), Processes: []Process{}, Warnings: []string{}}
	cid, err := s.Docker.container(ctx, id)
	if err != nil {
		return out, err
	}
	response, err := s.Docker.request(ctx, "GET", "/containers/"+cid+"/json")
	if err != nil {
		return out, err
	}
	var inspect struct {
		State struct {
			Running   bool
			StartedAt string
		}
		RestartCount int
	}
	err = json.NewDecoder(response.Body).Decode(&inspect)
	response.Body.Close()
	if err != nil {
		return out, err
	}
	out.Restarts = inspect.RestartCount
	out.StartedAt = inspect.State.StartedAt
	if inspect.State.Running {
		response, err = s.Docker.request(ctx, "GET", "/containers/"+cid+"/stats?stream=false")
		if err == nil {
			var stats struct {
				CPU struct {
					Usage struct {
						Total uint64 `json:"total_usage"`
					} `json:"cpu_usage"`
					System uint64 `json:"system_cpu_usage"`
					Online uint64 `json:"online_cpus"`
				} `json:"cpu_stats"`
				Previous struct {
					Usage struct {
						Total uint64 `json:"total_usage"`
					} `json:"cpu_usage"`
					System uint64 `json:"system_cpu_usage"`
				} `json:"precpu_stats"`
				Memory struct {
					Usage uint64            `json:"usage"`
					Limit uint64            `json:"limit"`
					Stats map[string]uint64 `json:"stats"`
				} `json:"memory_stats"`
				PIDs struct {
					Current uint64 `json:"current"`
				} `json:"pids_stats"`
			}
			err = json.NewDecoder(response.Body).Decode(&stats)
			response.Body.Close()
			if err == nil {
				if stats.CPU.System > stats.Previous.System && stats.CPU.Usage.Total >= stats.Previous.Usage.Total && stats.CPU.Online > 0 {
					cpu := float64(stats.CPU.Usage.Total-stats.Previous.Usage.Total) / float64(stats.CPU.System-stats.Previous.System) * float64(stats.CPU.Online) * 100
					out.CPUPercent = &cpu
				}
				used := stats.Memory.Usage
				cache := stats.Memory.Stats["inactive_file"]
				if cache == 0 {
					cache = stats.Memory.Stats["total_inactive_file"]
				}
				if cache < used {
					used -= cache
				}
				out.MemoryBytes = &used
				out.MemoryLimit = &stats.Memory.Limit
				out.PIDs = &stats.PIDs.Current
			}
		}
		if err != nil {
			out.Warnings = append(out.Warnings, "Live container metrics unavailable")
		}
		response, err = s.Docker.request(ctx, "GET", "/containers/"+cid+"/top?ps_args=-eo%20pid,comm,pcpu,pmem")
		if err == nil {
			var top struct {
				Titles    []string
				Processes [][]string
			}
			err = json.NewDecoder(response.Body).Decode(&top)
			response.Body.Close()
			if err == nil {
				for i, row := range top.Processes {
					if i >= 100 {
						break
					}
					if len(row) == 4 {
						out.Processes = append(out.Processes, Process{row[0], row[1], row[2], row[3]})
					}
				}
			}
		}
		if err != nil {
			out.Warnings = append(out.Warnings, "Process list unavailable")
		}
	}
	dir := filepath.Join(s.DataRoot, id)
	if s.DataRoot != "" {
		storageCache.Lock()
		sample, ok := storageCache.values[dir]
		storageCache.Unlock()
		if !ok || time.Since(sample.at) > 30*time.Second {
			sample = storageSample{at: time.Now()}
			root, e := os.OpenRoot(dir)
			if e == nil {
				count := 0
				sample.err = fs.WalkDir(root.FS(), ".", func(name string, d fs.DirEntry, e error) error {
					if e != nil {
						return e
					}
					count++
					if count > 200000 || time.Since(sample.at) > 5*time.Second {
						return context.DeadlineExceeded
					}
					if d.Type().IsRegular() {
						info, e := d.Info()
						if e != nil {
							return e
						}
						sample.bytes += uint64(info.Size())
					}
					return nil
				})
				root.Close()
			} else {
				sample.err = e
			}
			storageCache.Lock()
			storageCache.values[dir] = sample
			storageCache.Unlock()
		}
		if sample.err == nil {
			out.StorageBytes = &sample.bytes
			out.StorageSampledAt = sample.at.UTC().Format(time.RFC3339)
		} else {
			out.Warnings = append(out.Warnings, "Server storage size unavailable")
		}
		var stat unix.Statfs_t
		if unix.Statfs(dir, &stat) == nil {
			available, total := stat.Bavail*uint64(stat.Bsize), stat.Blocks*uint64(stat.Bsize)
			out.DiskAvailable = &available
			out.DiskTotal = &total
		}
	} else {
		out.Warnings = append(out.Warnings, "Server storage is not mounted")
	}
	return out, nil
}
func (s *Server) metricsRoute(mux *http.ServeMux) {
	mux.HandleFunc("GET /servers/{id}/metrics", func(w http.ResponseWriter, r *http.Request) {
		id := r.PathValue("id")
		if !Valid(id) {
			JSON(w, 404, map[string]string{"error": "unknown server"})
			return
		}
		ctx, cancel := context.WithTimeout(r.Context(), 12*time.Second)
		defer cancel()
		out, e := s.metrics(ctx, id)
		if e != nil {
			JSON(w, 503, map[string]string{"error": "Server metrics unavailable"})
			return
		}
		JSON(w, 200, out)
	})
}
