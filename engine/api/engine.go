// Package api 是 gomobile 的暴露面：只有这一个包会被编进 AAR。
package api

import (
	"crypto/sha1"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"path/filepath"
	"sort"

	"github.com/scooph/scooph/engine/bucket"
	"github.com/scooph/scooph/engine/manifest"
	"github.com/scooph/scooph/engine/version"
)

// Engine 是 Go 引擎的句柄。root 是引擎自己的工作目录，
// 后续任务在这里放仓库缓存、下载文件等。
type Engine struct {
	root string
}

// NewEngine 构造引擎并把工作目录固定下来。
func NewEngine(root string) *Engine {
	return &Engine{root: root}
}

// Version 返回引擎版本。M1 只需要一个非空值来验证跨语言链路。
func (e *Engine) Version() string {
	return "0.1.0"
}

// Sync 同步一个 bucket，返回 JSON 面向 Kotlin。
func (e *Engine) Sync(url string) (string, error) {
	res, err := bucket.Sync(e.bucketDir(url), url)
	if err != nil {
		return "", err
	}
	if res.Files == nil {
		res.Files = []string{}
	}
	out, err := json.Marshal(res)
	if err != nil {
		return "", fmt.Errorf("api: marshal sync result: %w", err)
	}
	return string(out), nil
}

// Load 同步后读取 apps 目录条目，返回 JSON：{"entries":[],"drops":[]}。
func (e *Engine) Load(url string) (string, error) {
	if _, err := bucket.Sync(e.bucketDir(url), url); err != nil {
		return "", err
	}
	entries, drops := manifest.LoadDir(filepath.Join(e.bucketDir(url), "apps"))
	if entries == nil {
		entries = []manifest.Entry{}
	}
	if drops == nil {
		drops = []manifest.Drop{}
	}
	out, err := json.Marshal(struct {
		Entries []manifest.Entry `json:"entries"`
		Drops   []manifest.Drop  `json:"drops"`
	}{Entries: entries, Drops: drops})
	if err != nil {
		return "", fmt.Errorf("api: marshal load result: %w", err)
	}
	return string(out), nil
}

// Plan 输入候选条目和已装快照，输出每个已装应用的生效条目与版本关系。
func (e *Engine) Plan(req string) (string, error) {
	type installedPkg struct {
		Package     string `json:"package"`
		VersionCode int64  `json:"versionCode"`
	}
	type planReq struct {
		Candidates []version.Candidate `json:"candidates"`
		Installed  []installedPkg      `json:"installed"`
	}
	type item struct {
		Package              string `json:"package"`
		Name                 string `json:"name"`
		Relation             string `json:"relation"`
		EntryVersionCode     int64  `json:"entryVersionCode"`
		InstalledVersionCode int64  `json:"installedVersionCode"`
		APKURL               string `json:"apkUrl"`
		SHA256               string `json:"sha256"`
		Size                 int64  `json:"size"`
		SourceID             string `json:"sourceId"`
	}

	var in planReq
	if err := json.Unmarshal([]byte(req), &in); err != nil {
		return "", fmt.Errorf("api: decode plan req: %w", err)
	}

	byPkg := make(map[string][]version.Candidate, len(in.Candidates))
	for _, c := range in.Candidates {
		if c.Entry.Package == "" {
			continue
		}
		byPkg[c.Entry.Package] = append(byPkg[c.Entry.Package], c)
	}

	items := make([]item, 0, len(in.Installed))
	for _, inst := range in.Installed {
		cands := byPkg[inst.Package]
		resolved, ok := version.Resolve(cands)
		if !ok {
			continue
		}
		rel := version.Relate(resolved.Entry.VersionCode, inst.VersionCode)
		items = append(items, item{
			Package:              inst.Package,
			Name:                 resolved.Entry.Name,
			Relation:             string(rel),
			EntryVersionCode:     resolved.Entry.VersionCode,
			InstalledVersionCode: inst.VersionCode,
			APKURL:               resolved.Entry.APK.URL,
			SHA256:               resolved.Entry.APK.SHA256,
			Size:                 resolved.Entry.APK.Size,
			SourceID:             resolved.SourceID,
		})
	}

	sort.Slice(items, func(i, j int) bool { return items[i].Package < items[j].Package })

	out, err := json.Marshal(struct {
		Items []item `json:"items"`
	}{Items: items})
	if err != nil {
		return "", fmt.Errorf("api: marshal plan result: %w", err)
	}
	return string(out), nil
}

func (e *Engine) bucketDir(url string) string {
	sum := sha1.Sum([]byte(url))
	return filepath.Join(e.root, "buckets", hex.EncodeToString(sum[:]))
}
