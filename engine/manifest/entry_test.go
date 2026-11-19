package manifest

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// hex64 是任意一个合法的 64 位十六进制 sha256，用作测试夹具。
const hex64 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

// validJSON 返回一个各字段都合法的条目 JSON。pkg 同时用作 package 字段，
// 调用方负责让文件名与之匹配。
func validJSON(pkg string) string {
	return `{"schema":1,"package":"` + pkg + `","name":"示例应用","versionCode":7000,` +
		`"versionName":"7.000","apk":{"url":"https://x/a.apk","size":6512345,` +
		`"sha256":"` + hex64 + `"},"source":"https://example.org/src","minSdk":26,"notes":"可选"}`
}

func TestParseEntryAcceptsValidEntry(t *testing.T) {
	e, err := ParseEntry("com.a.json", []byte(validJSON("com.a")))
	if err != nil {
		t.Fatalf("ParseEntry: %v", err)
	}
	if e.Schema != 1 {
		t.Errorf("Schema = %d, want 1", e.Schema)
	}
	if e.Package != "com.a" {
		t.Errorf("Package = %q, want com.a", e.Package)
	}
	if e.Name != "示例应用" {
		t.Errorf("Name = %q", e.Name)
	}
	if e.VersionCode != 7000 {
		t.Errorf("VersionCode = %d, want 7000", e.VersionCode)
	}
	if e.VersionName != "7.000" {
		t.Errorf("VersionName = %q, want 7.000", e.VersionName)
	}
	if e.APK.URL != "https://x/a.apk" {
		t.Errorf("APK.URL = %q", e.APK.URL)
	}
	if e.APK.Size != 6512345 {
		t.Errorf("APK.Size = %d", e.APK.Size)
	}
	if e.APK.SHA256 != hex64 {
		t.Errorf("APK.SHA256 = %q", e.APK.SHA256)
	}
	if e.Source != "https://example.org/src" {
		t.Errorf("Source = %q", e.Source)
	}
	if e.MinSdk != 26 {
		t.Errorf("MinSdk = %d, want 26", e.MinSdk)
	}
	if e.Notes != "可选" {
		t.Errorf("Notes = %q", e.Notes)
	}
}

func TestParseEntryRejectsMissingSHA256(t *testing.T) {
	_, err := ParseEntry("com.a.json", []byte(`{"package":"com.a","versionCode":1,"apk":{"url":"https://x/a.apk"}}`))
	var ve *ValidationError
	if !errors.As(err, &ve) || ve.Field != "apk.sha256" {
		t.Fatalf("got %v", err)
	}
	if ve.Reason == "" {
		t.Fatal("ValidationError.Reason 不能为空")
	}
	if ve.Error() == "" {
		t.Fatal("ValidationError.Error() 不能为空")
	}
}

func TestParseEntryIgnoresUnknownFields(t *testing.T) {
	e, err := ParseEntry("com.a.json", []byte(`{"package":"com.a","versionCode":1,"apk":{"url":"https://x/a.apk","sha256":"`+hex64+`"},"futureField":{"x":1}}`))
	if err != nil {
		t.Fatal(err)
	}
	if e.Package != "com.a" {
		t.Fatal(e.Package)
	}
}

// TestParseEntryValidationOrder 锁定计划里固定的校验顺序：
// JSON → package → apk.sha256 → apk.url → versionCode。
func TestParseEntryValidationOrder(t *testing.T) {
	cases := []struct {
		name     string
		fileName string
		data     string
		want     string
	}{
		{
			name:     "损坏 JSON 优先于一切",
			fileName: "com.a.json",
			data:     `{`,
			want:     "json",
		},
		{
			name:     "package 不符优先于缺 sha256",
			fileName: "com.a.json",
			data:     `{"package":"com.b","versionCode":0,"apk":{"url":"ftp://x/a.apk"}}`,
			want:     "package",
		},
		{
			name:     "sha256 优先于 url 与 versionCode",
			fileName: "com.a.json",
			data:     `{"package":"com.a","versionCode":0,"apk":{"url":"ftp://x/a.apk"}}`,
			want:     "apk.sha256",
		},
		{
			name:     "url 优先于 versionCode",
			fileName: "com.a.json",
			data:     `{"package":"com.a","versionCode":0,"apk":{"url":"ftp://x/a.apk","sha256":"` + hex64 + `"}}`,
			want:     "apk.url",
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			_, err := ParseEntry(tc.fileName, []byte(tc.data))
			var ve *ValidationError
			if !errors.As(err, &ve) {
				t.Fatalf("got %v, want *ValidationError", err)
			}
			if ve.Field != tc.want {
				t.Fatalf("Field = %q, want %q (err: %v)", ve.Field, tc.want, err)
			}
		})
	}
}

func TestParseEntryRejectsInvalidEntries(t *testing.T) {
	cases := []struct {
		name     string
		fileName string
		data     string
		field    string
	}{
		{
			name:     "空 package",
			fileName: "com.a.json",
			data:     `{"package":"","versionCode":1,"apk":{"url":"https://x/a.apk","sha256":"` + hex64 + `"}}`,
			field:    "package",
		},
		{
			name:     "文件名与 package 不符",
			fileName: "com.b.json",
			data:     `{"package":"com.a","versionCode":1,"apk":{"url":"https://x/a.apk","sha256":"` + hex64 + `"}}`,
			field:    "package",
		},
		{
			name:     "sha256 长度不足",
			fileName: "com.a.json",
			data:     `{"package":"com.a","versionCode":1,"apk":{"url":"https://x/a.apk","sha256":"` + hex64[:63] + `"}}`,
			field:    "apk.sha256",
		},
		{
			name:     "sha256 非十六进制",
			fileName: "com.a.json",
			data:     `{"package":"com.a","versionCode":1,"apk":{"url":"https://x/a.apk","sha256":"` + strings.Repeat("g", 64) + `"}}`,
			field:    "apk.sha256",
		},
		{
			name:     "url 为空",
			fileName: "com.a.json",
			data:     `{"package":"com.a","versionCode":1,"apk":{"url":"","sha256":"` + hex64 + `"}}`,
			field:    "apk.url",
		},
		{
			name:     "url 非 http(s)",
			fileName: "com.a.json",
			data:     `{"package":"com.a","versionCode":1,"apk":{"url":"ftp://x/a.apk","sha256":"` + hex64 + `"}}`,
			field:    "apk.url",
		},
		{
			name:     "versionCode 为 0",
			fileName: "com.a.json",
			data:     `{"package":"com.a","versionCode":0,"apk":{"url":"https://x/a.apk","sha256":"` + hex64 + `"}}`,
			field:    "versionCode",
		},
		{
			name:     "versionCode 为负",
			fileName: "com.a.json",
			data:     `{"package":"com.a","versionCode":-1,"apk":{"url":"https://x/a.apk","sha256":"` + hex64 + `"}}`,
			field:    "versionCode",
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			_, err := ParseEntry(tc.fileName, []byte(tc.data))
			var ve *ValidationError
			if !errors.As(err, &ve) {
				t.Fatalf("got %v, want *ValidationError", err)
			}
			if ve.Field != tc.field {
				t.Fatalf("Field = %q, want %q (err: %v)", ve.Field, tc.field, err)
			}
		})
	}
}

func TestParseEntryAcceptsHTTPAndHTTPS(t *testing.T) {
	for _, url := range []string{"http://x/a.apk", "https://x/a.apk"} {
		data := `{"package":"com.a","versionCode":1,"apk":{"url":"` + url + `","sha256":"` + hex64 + `"}}`
		if _, err := ParseEntry("com.a.json", []byte(data)); err != nil {
			t.Errorf("url %q 应被接受, got %v", url, err)
		}
	}
}

// Review Focus 1：apps 目录为空时返回空列表，不报错、不崩溃。
func TestLoadDirEmptyAppsDirectoryReturnsEmpty(t *testing.T) {
	dir := t.TempDir()

	entries, drops := LoadDir(dir)
	if len(entries) != 0 || len(drops) != 0 {
		t.Fatalf("entries=%d drops=%d, want 0/0", len(entries), len(drops))
	}
	//空切片而非 nil：跨 JSON 边界时应编码为 []，不是 null。
	if entries == nil || drops == nil {
		t.Fatalf("want non-nil empty slices, got entries=%v drops=%v", entries, drops)
	}
}

// Review Focus 1：整个仓库只有一个 bucket.json（没有 apps/ 目录）时同样返回空。
func TestLoadDirMissingAppsDirectoryReturnsEmpty(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "apps")

	entries, drops := LoadDir(dir)
	if len(entries) != 0 || len(drops) != 0 {
		t.Fatalf("entries=%d drops=%d, want 0/0", len(entries), len(drops))
	}
	if entries == nil || drops == nil {
		t.Fatalf("want non-nil empty slices, got entries=%v drops=%v", entries, drops)
	}
}

func TestLoadDirKeepsGoodEntriesAndDropsBadOnes(t *testing.T) {
	dir := t.TempDir()
	files := map[string]string{
		// 排序后坏文件在前，证明坏条目不会中断整次加载。
		"com.badjson.json": `{`,
		"com.good.json":    validJSON("com.good"),
		"com.nosha.json":   `{"package":"com.nosha","versionCode":1,"apk":{"url":"https://x/a.apk"}}`,
		"notes.txt":        "不是 JSON，应被忽略",
	}
	for name, content := range files {
		if err := os.WriteFile(filepath.Join(dir, name), []byte(content), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	// 子目录即使以 .json 结尾也不应被当作条目读取。
	if err := os.Mkdir(filepath.Join(dir, "sub.json"), 0o755); err != nil {
		t.Fatal(err)
	}

	entries, drops := LoadDir(dir)
	if len(entries) != 1 {
		t.Fatalf("entries=%d, want 1 (%+v)", len(entries), entries)
	}
	if entries[0].Package != "com.good" {
		t.Fatalf("entries[0].Package = %q, want com.good", entries[0].Package)
	}
	if len(drops) != 2 {
		t.Fatalf("drops=%d, want 2 (%+v)", len(drops), drops)
	}
	got := map[string]string{}
	for _, d := range drops {
		if d.File == "" || d.Reason == "" {
			t.Fatalf("Drop 的 File/Reason 不能为空: %+v", d)
		}
		got[d.File] = d.Reason
	}
	for _, want := range []string{"com.badjson.json", "com.nosha.json"} {
		if _, ok := got[want]; !ok {
			t.Fatalf("缺少 %s 的 Drop，got %+v", want, drops)
		}
	}
}

// 至少一个合法样例解析成功：fixture 是从 scooph-bucket 拷来的样例清单。
func TestLoadDirParsesTestdataSample(t *testing.T) {
	entries, drops := LoadDir(filepath.Join("..", "testdata", "apps"))
	if len(drops) != 0 {
		t.Fatalf("drops=%+v, want none", drops)
	}
	if len(entries) != 1 {
		t.Fatalf("entries=%d, want 1", len(entries))
	}
	e := entries[0]
	if e.Package != "com.fsck.k9" {
		t.Fatalf("Package = %q, want com.fsck.k9", e.Package)
	}
	if e.Name != "K-9 Mail" {
		t.Fatalf("Name = %q, want K-9 Mail", e.Name)
	}
	if e.VersionCode != 7000 {
		t.Fatalf("VersionCode = %d, want 7000", e.VersionCode)
	}
	if len(e.APK.SHA256) != 64 {
		t.Fatalf("APK.SHA256 = %q, want 64 hex chars", e.APK.SHA256)
	}
}
