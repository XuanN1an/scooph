package download

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// http.ServeContent 需要一个 modtime；零值表示不发 Last-Modified。
var zeroTime time.Time

// 200 + text/html 是私有仓库/登录页的典型回包，绝不能当 APK 存下来。
func TestFetchRejectsHTMLResponse(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.WriteHeader(http.StatusOK)
		_, _ = io.WriteString(w, "<html>login</html>")
	}))
	defer srv.Close()

	dest := filepath.Join(t.TempDir(), "app.apk")
	_, err := Fetch(srv.URL, dest, "", nil)
	if err == nil {
		t.Fatal("Fetch(html) = nil error, want error")
	}
	if !strings.Contains(err.Error(), "text/html") {
		t.Fatalf("error = %q, want it to mention content type", err)
	}
	assertNoResidue(t, dest)
}

func TestFetchDeletesPartialOnHashMismatch(t *testing.T) {
	body := []byte("apk-bytes")
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/octet-stream")
		_, _ = w.Write(body)
	}))
	defer srv.Close()

	dest := filepath.Join(t.TempDir(), "app.apk")
	wrong := strings.Repeat("0", 64)
	_, err := Fetch(srv.URL, dest, wrong, nil)
	if !errors.Is(err, ErrHashMismatch) {
		t.Fatalf("err = %v, want ErrHashMismatch", err)
	}
	assertNoResidue(t, dest)
}

// 写不进去时（磁盘满、目录不可写）返回可读错误，并且不留半截文件。
// 用「父目录不存在」制造写入失败：沙箱里没法真的把磁盘写满。
func TestFetchReturnsReadableErrorWhenWriteFails(t *testing.T) {
	body := []byte("apk-bytes")
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write(body)
	}))
	defer srv.Close()

	base := t.TempDir()
	dest := filepath.Join(base, "missing-dir", "app.apk")
	_, err := Fetch(srv.URL, dest, "", nil)
	if err == nil {
		t.Fatal("Fetch(unwritable) = nil error, want error")
	}
	assertNoResidue(t, dest)
}

func TestFetchRetriesFromOffset(t *testing.T) {
	content := []byte("0123456789abcdef")
	sum := sha256.Sum256(content)
	wantSHA := hex.EncodeToString(sum[:])

	var gotRange string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotRange = r.Header.Get("Range")
		http.ServeContent(w, r, "app.apk", zeroTime, bytes.NewReader(content))
	}))
	defer srv.Close()

	dir := t.TempDir()
	dest := filepath.Join(dir, "app.apk")
	part := dest + ".part"
	if err := os.WriteFile(part, content[:7], 0o644); err != nil {
		t.Fatalf("seed part: %v", err)
	}

	res, err := Fetch(srv.URL, dest, wantSHA, nil)
	if err != nil {
		t.Fatalf("Fetch resume: %v", err)
	}
	if gotRange != "bytes=7-" {
		t.Fatalf("Range = %q, want %q", gotRange, "bytes=7-")
	}
	got, err := os.ReadFile(dest)
	if err != nil {
		t.Fatalf("read dest: %v", err)
	}
	if !bytes.Equal(got, content) {
		t.Fatalf("dest content = %q, want %q", got, content)
	}
	if res.Size != int64(len(content)) {
		t.Fatalf("Size = %d, want %d", res.Size, len(content))
	}
	if res.SHA256 != wantSHA {
		t.Fatalf("SHA256 = %q, want %q", res.SHA256, wantSHA)
	}
	if _, err := os.Stat(part); !os.IsNotExist(err) {
		t.Fatalf("part file still exists after success: %v", err)
	}
}

// Server 忽略 Range 并回整份内容时，续传必须从头写，不能叠加两份。
func TestFetchRestartsWhenServerIgnoresRange(t *testing.T) {
	content := []byte("fresh-content")
	sum := sha256.Sum256(content)
	wantSHA := hex.EncodeToString(sum[:])

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write(content)
	}))
	defer srv.Close()

	dir := t.TempDir()
	dest := filepath.Join(dir, "app.apk")
	if err := os.WriteFile(dest+".part", []byte("stale"), 0o644); err != nil {
		t.Fatalf("seed part: %v", err)
	}

	if _, err := Fetch(srv.URL, dest, wantSHA, nil); err != nil {
		t.Fatalf("Fetch restart: %v", err)
	}
	got, err := os.ReadFile(dest)
	if err != nil {
		t.Fatalf("read dest: %v", err)
	}
	if !bytes.Equal(got, content) {
		t.Fatalf("dest content = %q, want %q", got, content)
	}
}

func TestFetchMapsUnauthorizedToPrivateRepo(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
	}))
	defer srv.Close()

	dest := filepath.Join(t.TempDir(), "app.apk")
	_, err := Fetch(srv.URL, dest, "", nil)
	if !errors.Is(err, ErrPrivateRepo) {
		t.Fatalf("err = %v, want ErrPrivateRepo", err)
	}
	assertNoResidue(t, dest)
}

func assertNoResidue(t *testing.T, dest string) {
	t.Helper()
	for _, path := range []string{dest, dest + ".part"} {
		if _, err := os.Stat(path); !os.IsNotExist(err) {
			t.Fatalf("%s should not exist after failure (err = %v)", path, err)
		}
	}
}
