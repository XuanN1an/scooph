package bucket

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

// hex64 是任意一个合法的 64 位十六进制 sha256，用作测试夹具。
const hex64 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

// appJSON 返回一个尽量贴近真实 bucket 条目形状的 JSON。
// Sync 只关心文件名，内容不参与解析，但保持真实可以避免夹具误导后来的人。
func appJSON(pkg string) string {
	return `{"schema":1,"package":"` + pkg + `","name":"` + pkg + `","versionCode":1,"versionName":"1",` +
		`"apk":{"url":"https://example.org/a.apk","size":1,"sha256":"` + hex64 + `"}}`
}

// appsFixture 返回一个只有 bucket.json 与指定 apps 文件的工作区内容。
func appsFixture(apps ...string) map[string]string {
	files := map[string]string{"bucket.json": `{"schema":1}`}
	for _, name := range apps {
		files["apps/"+name] = appJSON(strings.TrimSuffix(name, ".json"))
	}
	return files
}

// gitFixture 是测试用的远端裸仓库 + 一个可提交的工作仓库。
// 远端用本机 git 命令造出来，Sync 只通过 URL 读取它。
type gitFixture struct {
	remote string
	work   string
}

func requireGit(t *testing.T) {
	t.Helper()
	if _, err := exec.LookPath("git"); err != nil {
		t.Skip("本机没有 git 命令，跳过需要 fixture 的测试")
	}
}

func runGit(t *testing.T, dir string, args ...string) string {
	t.Helper()
	cmd := exec.Command("git", args...)
	cmd.Dir = dir
	// 固定提交者身份，避免测试依赖本机 git 全局配置。
	cmd.Env = append(os.Environ(),
		"GIT_AUTHOR_NAME=Scooph Test",
		"GIT_AUTHOR_EMAIL=test@example.com",
		"GIT_COMMITTER_NAME=Scooph Test",
		"GIT_COMMITTER_EMAIL=test@example.com",
	)
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("git %s: %v\n%s", strings.Join(args, " "), err, out)
	}
	return strings.TrimSpace(string(out))
}

// newGitFixture 造一个 main 分支的裸仓库远端与工作仓库，并把 initial 推上去。
func newGitFixture(t *testing.T, initial map[string]string) *gitFixture {
	t.Helper()
	requireGit(t)

	root := t.TempDir()
	f := &gitFixture{
		remote: filepath.Join(root, "remote.git"),
		work:   filepath.Join(root, "work"),
	}
	runGit(t, root, "init", "--bare", "--initial-branch=main", f.remote)
	runGit(t, root, "init", "--initial-branch=main", f.work)
	runGit(t, f.work, "remote", "add", "origin", f.remote)
	f.commitAndPush(t, initial, "initial")
	return f
}

// commitAndPush 把 files 写进工作仓库（相对路径，使用 / 分隔）并推送到远端，
// 返回推送后的提交哈希。
func (f *gitFixture) commitAndPush(t *testing.T, files map[string]string, message string) string {
	t.Helper()
	for name, content := range files {
		p := filepath.Join(f.work, filepath.FromSlash(name))
		if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(p, []byte(content), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	runGit(t, f.work, "add", "-A")
	if runGit(t, f.work, "status", "--porcelain") != "" {
		runGit(t, f.work, "commit", "-m", message)
	}
	runGit(t, f.work, "push", "origin", "main")
	return f.head(t)
}

func (f *gitFixture) head(t *testing.T) string {
	t.Helper()
	return runGit(t, f.work, "rev-parse", "HEAD")
}

// Result 是跨语言 JSON 面的一部分，字段名锁定为 commit/files/empty。
func TestResultJSONFieldNames(t *testing.T) {
	b, err := json.Marshal(Result{Commit: "abc123", Files: []string{"apps/a.json"}, Empty: false})
	if err != nil {
		t.Fatal(err)
	}
	const want = `{"commit":"abc123","files":["apps/a.json"],"empty":false}`
	if string(b) != want {
		t.Fatalf("json = %s, want %s", b, want)
	}
}

// 初次同步：dir 不存在 -> 浅克隆，Files 是排序后的 apps/*.json 相对路径。
func TestSyncClonesMissingDirAndListsApps(t *testing.T) {
	files := appsFixture("com.c.json", "com.a.json", "com.b.json")
	// 干扰项：非 JSON 文件与子目录里的 JSON 都不该出现在 Files 里。
	files["apps/notes.txt"] = "不是清单"
	files["apps/nested/com.z.json"] = appJSON("com.z")
	f := newGitFixture(t, files)
	wantCommit := f.head(t)
	dir := filepath.Join(t.TempDir(), "bucket")

	res, err := Sync(dir, f.remote)
	if err != nil {
		t.Fatalf("Sync: %v", err)
	}
	if res.Empty {
		t.Fatal("Empty = true, want false")
	}
	wantFiles := []string{"apps/com.a.json", "apps/com.b.json", "apps/com.c.json"}
	if !reflect.DeepEqual(res.Files, wantFiles) {
		t.Fatalf("Files = %v, want %v", res.Files, wantFiles)
	}
	if res.Commit != wantCommit {
		t.Fatalf("Commit = %q, want %q", res.Commit, wantCommit)
	}

	// 浅克隆：fixture 远端有 2 个提交，克隆深度 1 只能看到最新的 1 个。
	if got := runGit(t, dir, "rev-list", "--count", "HEAD"); got != "1" {
		t.Fatalf("rev-list --count HEAD = %q, want 1（浅克隆）", got)
	}

	// 第二次同步没有新提交时结果稳定，不报错。
	again, err := Sync(dir, f.remote)
	if err != nil {
		t.Fatalf("第二次 Sync: %v", err)
	}
	if !reflect.DeepEqual(again, res) {
		t.Fatalf("第二次 Sync = %+v, want %+v", again, res)
	}
}

// 增量同步：已有 repo 时 fetch 并快进到 origin/HEAD，Files 反映新提交。
func TestSyncFastForwardsExistingRepo(t *testing.T) {
	f := newGitFixture(t, appsFixture("com.a.json", "com.b.json", "com.c.json"))
	dir := filepath.Join(t.TempDir(), "bucket")

	first, err := Sync(dir, f.remote)
	if err != nil {
		t.Fatalf("初次 Sync: %v", err)
	}
	if len(first.Files) != 3 {
		t.Fatalf("初次 Files = %v, want 3 个", first.Files)
	}

	wantCommit := f.commitAndPush(t, map[string]string{
		"apps/com.d.json": appJSON("com.d"),
	}, "add com.d")

	second, err := Sync(dir, f.remote)
	if err != nil {
		t.Fatalf("增量 Sync: %v", err)
	}
	if second.Commit != wantCommit {
		t.Fatalf("Commit = %q, want %q", second.Commit, wantCommit)
	}
	if second.Commit == first.Commit {
		t.Fatal("增量同步后 Commit 未变化")
	}
	wantFiles := []string{"apps/com.a.json", "apps/com.b.json", "apps/com.c.json", "apps/com.d.json"}
	if !reflect.DeepEqual(second.Files, wantFiles) {
		t.Fatalf("Files = %v, want %v", second.Files, wantFiles)
	}
	// 工作区也得真的快进，而不只是 ref 变了。
	if _, err := os.Stat(filepath.Join(dir, "apps", "com.d.json")); err != nil {
		t.Fatalf("快进后工作区缺少 com.d.json: %v", err)
	}
}

// Review Focus 1：整个仓库只有一个 bucket.json 时返回空 bucket，不报错。
func TestSyncOnlyBucketJSONIsEmpty(t *testing.T) {
	f := newGitFixture(t, map[string]string{"bucket.json": `{"schema":1}`})
	dir := filepath.Join(t.TempDir(), "bucket")

	res, err := Sync(dir, f.remote)
	if err != nil {
		t.Fatalf("Sync: %v", err)
	}
	if !res.Empty {
		t.Fatal("Empty = false, want true")
	}
	if res.Files == nil {
		t.Fatal("Files 应为非 nil 空切片，才能编码成 [] 而不是 null")
	}
	if len(res.Files) != 0 {
		t.Fatalf("Files = %v, want 空", res.Files)
	}
	if res.Commit == "" {
		t.Fatal("Commit 不能为空：空 bucket 也需要记录 commit")
	}
}

// Review Focus 1：apps/ 存在但为空（只有 .gitkeep）时同样返回空 bucket。
func TestSyncEmptyAppsDirectoryIsEmpty(t *testing.T) {
	f := newGitFixture(t, map[string]string{
		"bucket.json":   `{"schema":1}`,
		"apps/.gitkeep": "",
	})
	dir := filepath.Join(t.TempDir(), "bucket")

	res, err := Sync(dir, f.remote)
	if err != nil {
		t.Fatalf("Sync: %v", err)
	}
	if !res.Empty {
		t.Fatalf("Empty = false, want true (Files=%v)", res.Files)
	}
}

// spec §10.6：鉴权失败要返回 ErrPrivateRepo，而不是一个网络错误码。
// 401（需要认证）与 403（无权限）都属于「私有仓库暂不支持」。
func TestSyncAuthFailureReturnsErrPrivateRepo(t *testing.T) {
	for _, status := range []int{http.StatusUnauthorized, http.StatusForbidden} {
		t.Run(http.StatusText(status), func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(status)
			}))
			defer srv.Close()
			dir := filepath.Join(t.TempDir(), "bucket")

			_, err := Sync(dir, srv.URL+"/owner/private-bucket.git")
			if err == nil {
				t.Fatal("want error, got nil")
			}
			if !errors.Is(err, ErrPrivateRepo) {
				t.Fatalf("err = %v, want ErrPrivateRepo", err)
			}
			if !strings.Contains(err.Error(), "private repository not supported") {
				t.Fatalf("错误信息应写明私有仓库暂不支持, got %q", err.Error())
			}
		})
	}
}

// 404 是「仓库不存在」，不能被误报成私有仓库。
func TestSyncMissingRemoteIsNotPrivateRepo(t *testing.T) {
	srv := httptest.NewServer(http.NotFoundHandler())
	defer srv.Close()
	dir := filepath.Join(t.TempDir(), "bucket")

	_, err := Sync(dir, srv.URL+"/owner/missing.git")
	if err == nil {
		t.Fatal("want error, got nil")
	}
	if errors.Is(err, ErrPrivateRepo) {
		t.Fatalf("err = %v, want 非 ErrPrivateRepo", err)
	}
}
