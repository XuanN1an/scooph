// Package download 下载 APK 并校验 sha256。
//
// 半截内容只写在 destPath + ".part"：校验通过才改名成正式文件，
// 任何失败都删掉 .part，调用方永远看不到「看起来完整」的坏文件。
package download

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
)

// Progress 接收下载进度。total 为 0 表示服务端没给 Content-Length。
type Progress interface {
	OnProgress(done, total int64)
}

// FetchResult 是一次成功下载的结果。
type FetchResult struct {
	Path   string `json:"path"`
	Size   int64  `json:"size"`
	SHA256 string `json:"sha256"`
}

var (
	// ErrHashMismatch 表示下载完成但 sha256 与清单里的不一致。
	ErrHashMismatch = errors.New("sha256 mismatch")
	// ErrPrivateRepo 表示服务端要求认证（401/403），私有仓库 v1 不支持。
	ErrPrivateRepo = errors.New("private repository not supported")
)

// Fetch 把 url 下载到 destPath，边下边算 sha256。
//
// 已存在 .part 时带 Range 续传；服务端忽略 Range 回整份内容时从头重写。
// wantSHA256 为空表示跳过校验（调用方自己保证来源可信）。
func Fetch(url, destPath, wantSHA256 string, p Progress) (FetchResult, error) {
	partPath := destPath + ".part"

	offset, err := existingSize(partPath)
	if err != nil {
		return FetchResult{}, err
	}

	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		return FetchResult{}, fmt.Errorf("download: build request: %w", err)
	}
	if offset > 0 {
		req.Header.Set("Range", fmt.Sprintf("bytes=%d-", offset))
	}

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		removeQuietly(partPath)
		return FetchResult{}, fmt.Errorf("download: get %s: %w", url, err)
	}
	defer resp.Body.Close()

	switch resp.StatusCode {
	case http.StatusOK:
		// 服务端忽略了 Range：这次回的是整份内容，从头写。
		offset = 0
	case http.StatusPartialContent:
	default:
		removeQuietly(partPath)
		if resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden {
			return FetchResult{}, fmt.Errorf("%w: status %d", ErrPrivateRepo, resp.StatusCode)
		}
		return FetchResult{}, fmt.Errorf("download: unexpected status %d", resp.StatusCode)
	}

	contentType := strings.ToLower(resp.Header.Get("Content-Type"))
	if strings.HasPrefix(contentType, "text/html") {
		removeQuietly(partPath)
		return FetchResult{}, fmt.Errorf(
			"download: unexpected content-type %q (status %d)",
			contentType, resp.StatusCode,
		)
	}

	f, err := os.OpenFile(partPath, os.O_CREATE|os.O_RDWR, 0o644)
	if err != nil {
		return FetchResult{}, fmt.Errorf("download: open %s: %w", partPath, err)
	}

	hash := sha256.New()
	if offset > 0 {
		// 续传时，文件里已有的前缀同样要进哈希。
		if _, err := io.CopyN(hash, f, offset); err != nil {
			_ = f.Close()
			removeQuietly(partPath)
			return FetchResult{}, fmt.Errorf("download: hash existing part: %w", err)
		}
	}
	if err := f.Truncate(offset); err != nil {
		_ = f.Close()
		removeQuietly(partPath)
		return FetchResult{}, fmt.Errorf("download: truncate %s: %w", partPath, err)
	}
	if _, err := f.Seek(offset, io.SeekStart); err != nil {
		_ = f.Close()
		removeQuietly(partPath)
		return FetchResult{}, fmt.Errorf("download: seek %s: %w", partPath, err)
	}

	total := int64(0)
	if resp.ContentLength >= 0 {
		total = offset + resp.ContentLength
	}

	written := offset
	buf := make([]byte, 64*1024)
	for {
		n, readErr := resp.Body.Read(buf)
		if n > 0 {
			if _, writeErr := f.Write(buf[:n]); writeErr != nil {
				_ = f.Close()
				removeQuietly(partPath)
				return FetchResult{}, fmt.Errorf("download: write %s: %w", partPath, writeErr)
			}
			_, _ = hash.Write(buf[:n])
			written += int64(n)
			if p != nil {
				p.OnProgress(written, total)
			}
		}
		if readErr == io.EOF {
			break
		}
		if readErr != nil {
			_ = f.Close()
			removeQuietly(partPath)
			return FetchResult{}, fmt.Errorf("download: read body: %w", readErr)
		}
	}
	if err := f.Close(); err != nil {
		removeQuietly(partPath)
		return FetchResult{}, fmt.Errorf("download: close %s: %w", partPath, err)
	}

	gotSHA := hex.EncodeToString(hash.Sum(nil))
	if wantSHA256 != "" && !strings.EqualFold(gotSHA, wantSHA256) {
		removeQuietly(partPath)
		return FetchResult{}, fmt.Errorf("%w: got %s want %s", ErrHashMismatch, gotSHA, wantSHA256)
	}

	if err := os.Rename(partPath, destPath); err != nil {
		removeQuietly(partPath)
		return FetchResult{}, fmt.Errorf("download: rename %s: %w", partPath, err)
	}

	return FetchResult{Path: destPath, Size: written, SHA256: gotSHA}, nil
}

// existingSize 返回 .part 已有字节数；不存在时是 0。
func existingSize(path string) (int64, error) {
	info, err := os.Stat(path)
	if err == nil {
		return info.Size(), nil
	}
	if os.IsNotExist(err) {
		return 0, nil
	}
	return 0, fmt.Errorf("download: stat %s: %w", path, err)
}

func removeQuietly(path string) {
	_ = os.Remove(path)
}
