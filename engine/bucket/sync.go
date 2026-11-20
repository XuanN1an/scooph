// Package bucket 负责把远端 git 仓库同步到本地缓存目录。
package bucket

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path"
	"path/filepath"
	"sort"
	"strings"
	"time"

	git "github.com/go-git/go-git/v5"
	"github.com/go-git/go-git/v5/plumbing/transport"
)

// syncTimeout 限住一次同步的总时长。没有它，网络被代理挂住时
// 来源会一直停在 PENDING，用户永远等不到「同步失败」。
const syncTimeout = 90 * time.Second

// Result 是一次同步的结果，字段名是与 Kotlin 侧的 JSON 契约。
type Result struct {
	Commit string   `json:"commit"`
	Files  []string `json:"files"`
	Empty  bool     `json:"empty"`
}

// ErrPrivateRepo 表示远端要求鉴权。v1 只支持公开仓库（spec §10.6）。
var ErrPrivateRepo = errors.New("private repository not supported")

// Sync 把 dir 同步到 url 指向的 bucket 仓库。
// dir 不存在（或还不是 git 仓库）时浅克隆；已经是仓库时 fetch 并快进到 origin/HEAD。
// url 既可以是 https URL，也可以是本机路径（测试用）。
func Sync(dir, url string) (Result, error) {
	ctx, cancel := context.WithTimeout(context.Background(), syncTimeout)
	defer cancel()

	repo, err := openOrClone(ctx, dir, url)
	if err != nil {
		return Result{}, err
	}

	head, err := repo.Head()
	if err != nil {
		return Result{}, fmt.Errorf("bucket: resolve HEAD: %w", err)
	}

	files, err := listApps(dir)
	if err != nil {
		return Result{}, err
	}

	return Result{
		Commit: head.Hash().String(),
		Files:  files,
		Empty:  len(files) == 0,
	}, nil
}

// openOrClone 复用已有仓库，或在目录尚未成为仓库时浅克隆。
func openOrClone(ctx context.Context, dir, url string) (*git.Repository, error) {
	repo, err := git.PlainOpen(dir)
	if err == nil {
		if err := update(ctx, repo, url); err != nil {
			return nil, err
		}
		return repo, nil
	}
	if !errors.Is(err, git.ErrRepositoryNotExists) {
		return nil, fmt.Errorf("bucket: open %s: %w", dir, err)
	}

	_, statErr := os.Stat(dir)
	existed := statErr == nil

	repo, err = git.PlainCloneContext(ctx, dir, false, &git.CloneOptions{
		URL:   url,
		Depth: 1, // bucket 只需要最新快照，不保留历史。
	})
	if err != nil {
		// 半截克隆目录会让下一次同步也起不来，直接清掉。
		if !existed {
			_ = os.RemoveAll(dir)
		}
		if ctx.Err() != nil {
			return nil, syncTimeoutErr()
		}
		return nil, wrapSyncErr("clone", err)
	}
	return repo, nil
}

// update 从远端拉取，并把工作区快进到远端 HEAD。
func update(ctx context.Context, repo *git.Repository, url string) error {
	wt, err := repo.Worktree()
	if err != nil {
		return fmt.Errorf("bucket: open worktree: %w", err)
	}

	// 只有浅克隆才能继续用 Depth 拉取；非浅仓库按完整历史拉取。
	depth := 0
	if shallows, err := repo.Storer.Shallow(); err == nil && len(shallows) > 0 {
		depth = 1
	}

	err = wt.PullContext(ctx, &git.PullOptions{
		RemoteName: "origin",
		RemoteURL:  url,
		Depth:      depth,
	})
	if err == nil || errors.Is(err, git.NoErrAlreadyUpToDate) {
		return nil
	}
	if ctx.Err() != nil {
		return syncTimeoutErr()
	}
	return wrapSyncErr("fetch", err)
}

// syncTimeoutErr 是给 Kotlin 侧看的超时原因。
func syncTimeoutErr() error {
	return fmt.Errorf("bucket: sync timeout after %s", syncTimeout)
}

// wrapSyncErr 把 go-git 的鉴权错误换成对用户可读的 ErrPrivateRepo，
// 其余错误补上操作名，便于调用方定位失败阶段。
func wrapSyncErr(op string, err error) error {
	if isAuthErr(err) {
		return fmt.Errorf("%w: %s: %v", ErrPrivateRepo, op, err)
	}
	return fmt.Errorf("bucket: %s: %w", op, err)
}

// isAuthErr 判断 go-git 是否因为要求凭据而失败（401/403）。
func isAuthErr(err error) bool {
	return errors.Is(err, transport.ErrAuthenticationRequired) ||
		errors.Is(err, transport.ErrAuthorizationFailed)
}

// listApps 返回 apps/*.json 的相对路径（统一用 / 分隔）并排序。
// 没有 apps/ 目录或目录里没有清单时返回非 nil 空切片。
func listApps(dir string) ([]string, error) {
	appsDir := filepath.Join(dir, "apps")
	entries, err := os.ReadDir(appsDir)
	if err != nil {
		if os.IsNotExist(err) {
			return []string{}, nil
		}
		return nil, fmt.Errorf("bucket: read %s: %w", appsDir, err)
	}

	files := make([]string, 0, len(entries))
	for _, de := range entries {
		if de.IsDir() {
			continue
		}
		name := de.Name()
		if !strings.EqualFold(filepath.Ext(name), ".json") {
			continue
		}
		// Files 是 JSON 面里的相对路径，不随宿主平台变化。
		files = append(files, path.Join("apps", name))
	}
	sort.Strings(files)
	return files, nil
}
