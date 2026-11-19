package version

import (
	"sort"

	"github.com/scooph/scooph/engine/manifest"
)

// Relation 是清单版本与本机已安装版本之间的关系。
type Relation string

const (
	// Newer 表示清单版本比已安装版本更新，或者本机尚未安装。
	Newer Relation = "newer"
	// Same 表示清单版本与已安装版本相同。
	Same Relation = "same"
	// Older 表示清单版本比已安装版本更旧。
	Older Relation = "older"
)

// Relate 比较清单条目与本机已安装应用。
// installedVersionCode == 0 表示未安装（部分 ROM 与老应用如此），一律视为可更新，
// 避免出现负数比较或「本机版本更新」的误判。
func Relate(entryVersionCode, installedVersionCode int64) Relation {
	if installedVersionCode == 0 {
		return Newer
	}
	switch {
	case entryVersionCode > installedVersionCode:
		return Newer
	case entryVersionCode == installedVersionCode:
		return Same
	default:
		return Older
	}
}

// Candidate 是同一 package 在一个源里的清单条目，SourceOrder 是该源被添加的先后顺序。
type Candidate struct {
	Entry       manifest.Entry
	SourceID    string
	SourceOrder int
}

// Resolve 在多个源描述同一个包时确定性地选出一条。
// 优先级：VersionCode 最大 -> SourceOrder 最小 -> SourceID 字典序最小。
// 排序而不是逐次比较，结果不依赖调用方传入顺序或 map 遍历顺序。
func Resolve(cands []Candidate) (Candidate, bool) {
	if len(cands) == 0 {
		return Candidate{}, false
	}

	// 复制后再排序，不改动调用方的切片。
	sorted := make([]Candidate, len(cands))
	copy(sorted, cands)

	sort.SliceStable(sorted, func(i, j int) bool {
		a, b := sorted[i], sorted[j]
		if a.Entry.VersionCode != b.Entry.VersionCode {
			return a.Entry.VersionCode > b.Entry.VersionCode
		}
		if a.SourceOrder != b.SourceOrder {
			return a.SourceOrder < b.SourceOrder
		}
		return a.SourceID < b.SourceID
	})

	return sorted[0], true
}
