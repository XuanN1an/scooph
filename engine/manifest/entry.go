package manifest

import (
	"encoding/json"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strings"
)

// APK 描述可下载安装包的位置与校验信息。
type APK struct {
	URL    string `json:"url"`
	Size   int64  `json:"size"`
	SHA256 string `json:"sha256"`
}

// Entry 是 bucket 里单个应用条目。
type Entry struct {
	Schema      int    `json:"schema"`
	Package     string `json:"package"`
	Name        string `json:"name"`
	VersionCode int64  `json:"versionCode"`
	VersionName string `json:"versionName"`
	APK         APK    `json:"apk"`
	Source      string `json:"source"`
	MinSdk      int    `json:"minSdk"`
	Notes       string `json:"notes"`
}

// Drop 记录被跳过的条目与原因。
type Drop struct {
	File   string `json:"file"`
	Reason string `json:"reason"`
}

// ValidationError 表示条目字段校验失败。
type ValidationError struct {
	Field  string
	Reason string
}

func (e *ValidationError) Error() string {
	if e == nil {
		return ""
	}
	return fmt.Sprintf("invalid %s: %s", e.Field, e.Reason)
}

func invalid(field, reason string) *ValidationError {
	return &ValidationError{Field: field, Reason: reason}
}

// ParseEntry 解析并校验单个条目。
// 校验顺序固定：JSON -> package -> apk.sha256 -> apk.url -> versionCode。
func ParseEntry(fileName string, data []byte) (Entry, error) {
	var e Entry
	if err := json.Unmarshal(data, &e); err != nil {
		return Entry{}, invalid("json", err.Error())
	}

	wantPkg := strings.TrimSuffix(filepath.Base(fileName), filepath.Ext(fileName))
	if strings.TrimSpace(e.Package) == "" {
		return Entry{}, invalid("package", "package is empty")
	}
	if wantPkg != "" && e.Package != wantPkg {
		return Entry{}, invalid("package", fmt.Sprintf("package %q does not match file %q", e.Package, wantPkg))
	}

	if !isHex64(e.APK.SHA256) {
		return Entry{}, invalid("apk.sha256", "must be 64 hex characters")
	}

	u, err := url.Parse(strings.TrimSpace(e.APK.URL))
	if err != nil || u == nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		return Entry{}, invalid("apk.url", "must be http(s) URL")
	}

	if e.VersionCode < 1 {
		return Entry{}, invalid("versionCode", "must be >= 1")
	}

	return e, nil
}

// LoadDir 读取目录下的 .json 条目。
// 单文件失败会落 Drop，不中断整次读取。
func LoadDir(dir string) (entries []Entry, drops []Drop) {
	entries = make([]Entry, 0)
	drops = make([]Drop, 0)

	list, err := os.ReadDir(dir)
	if err != nil {
		if os.IsNotExist(err) {
			return entries, drops
		}
		return entries, []Drop{{File: dir, Reason: err.Error()}}
	}

	for _, de := range list {
		if de.IsDir() {
			continue
		}
		name := de.Name()
		if !strings.EqualFold(filepath.Ext(name), ".json") {
			continue
		}
		b, err := os.ReadFile(filepath.Join(dir, name))
		if err != nil {
			drops = append(drops, Drop{File: name, Reason: err.Error()})
			continue
		}
		e, err := ParseEntry(name, b)
		if err != nil {
			drops = append(drops, Drop{File: name, Reason: err.Error()})
			continue
		}
		entries = append(entries, e)
	}

	return entries, drops
}

func isHex64(s string) bool {
	if len(s) != 64 {
		return false
	}
	for _, r := range s {
		if (r >= '0' && r <= '9') || (r >= 'a' && r <= 'f') || (r >= 'A' && r <= 'F') {
			continue
		}
		return false
	}
	return true
}
