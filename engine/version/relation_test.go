package version

import (
	"math/rand"
	"testing"

	"github.com/scooph/scooph/engine/manifest"
)

// mk 构造一个只关心版本号与 sha256 的候选条目。
func mk(versionCode int64, sha256 string) manifest.Entry {
	return manifest.Entry{VersionCode: versionCode, APK: manifest.APK{SHA256: sha256}}
}

func TestRelate(t *testing.T) {
	tests := []struct {
		name      string
		entry     int64
		installed int64
		want      Relation
	}{
		{"entry newer", 7000, 6000, Newer},
		{"same version", 7000, 7000, Same},
		{"entry older", 6000, 7000, Older},
		// Review Focus 4：installed == 0 视为未安装，一律 newer。
		{"installed zero is uninstalled", 7000, 0, Newer},
		{"entry one vs installed zero", 1, 0, Newer},
		{"both zero still newer", 0, 0, Newer},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := Relate(tt.entry, tt.installed); got != tt.want {
				t.Fatalf("Relate(%d, %d) = %q, want %q", tt.entry, tt.installed, got, tt.want)
			}
		})
	}
}

func TestRelationStringValues(t *testing.T) {
	if Newer != Relation("newer") || Same != Relation("same") || Older != Relation("older") {
		t.Fatalf("relation constants = %q/%q/%q", Newer, Same, Older)
	}
}

func TestResolveEmptyReturnsFalse(t *testing.T) {
	got, ok := Resolve(nil)
	if ok {
		t.Fatalf("Resolve(nil) = %+v, true; want false", got)
	}
	if _, ok := Resolve([]Candidate{}); ok {
		t.Fatal("Resolve(empty) = true; want false")
	}
}

func TestResolvePrefersHighestVersionCode(t *testing.T) {
	cands := []Candidate{
		{Entry: mk(6999, "aaa"), SourceID: "a", SourceOrder: 0},
		{Entry: mk(7001, "ccc"), SourceID: "z", SourceOrder: 9},
		{Entry: mk(7000, "bbb"), SourceID: "m", SourceOrder: 5},
	}
	for _, order := range [][]Candidate{
		cands,
		{cands[2], cands[0], cands[1]},
		{cands[1], cands[2], cands[0]},
	} {
		got, ok := Resolve(order)
		if !ok {
			t.Fatal("Resolve = false, want true")
		}
		if got.Entry.VersionCode != 7001 || got.SourceID != "z" {
			t.Fatalf("Resolve = %+v, want versionCode 7001 source z", got)
		}
	}
}

// Review Focus 3：同版本、不同 sha256 时，取添加顺序更早（SourceOrder 更小）的源。
func TestResolvePrefersEarliestSourceOnTie(t *testing.T) {
	got, ok := Resolve([]Candidate{
		{Entry: mk(7000, "aaa"), SourceID: "z", SourceOrder: 3},
		{Entry: mk(7000, "bbb"), SourceID: "a", SourceOrder: 1},
	})
	if !ok || got.SourceID != "a" {
		t.Fatalf("got %+v, want source a (order 1)", got)
	}
	if got.Entry.APK.SHA256 != "bbb" {
		t.Fatalf("SHA256 = %q, want bbb", got.Entry.APK.SHA256)
	}
}

func TestResolveTieBreaksBySourceID(t *testing.T) {
	got, ok := Resolve([]Candidate{
		{Entry: mk(7000, "aaa"), SourceID: "b", SourceOrder: 2},
		{Entry: mk(7000, "bbb"), SourceID: "a", SourceOrder: 2},
	})
	if !ok || got.SourceID != "a" {
		t.Fatalf("got %+v, want source a (lexicographically smaller)", got)
	}
}

// 同一组输入打乱 20 次，结果必须完全一致：不能依赖 map 遍历或输入顺序。
func TestResolveIsDeterministicAcrossShuffles(t *testing.T) {
	base := []Candidate{
		{Entry: mk(7000, "a1"), SourceID: "a", SourceOrder: 1},
		{Entry: mk(7000, "b1"), SourceID: "b", SourceOrder: 1},
		{Entry: mk(7000, "c2"), SourceID: "c", SourceOrder: 2},
		{Entry: mk(6999, "d0"), SourceID: "d", SourceOrder: 0},
		{Entry: mk(7001, "x8"), SourceID: "x", SourceOrder: 8},
		{Entry: mk(7001, "y9"), SourceID: "y", SourceOrder: 9},
		{Entry: mk(7001, "z9"), SourceID: "z", SourceOrder: 9},
	}

	want, ok := Resolve(base)
	if !ok {
		t.Fatal("Resolve(base) = false")
	}
	if want.Entry.VersionCode != 7001 || want.SourceID != "x" {
		t.Fatalf("Resolve(base) = %+v, want versionCode 7001 source x", want)
	}

	rng := rand.New(rand.NewSource(42))
	for i := 0; i < 20; i++ {
		shuffled := make([]Candidate, len(base))
		copy(shuffled, base)
		rng.Shuffle(len(shuffled), func(a, b int) {
			shuffled[a], shuffled[b] = shuffled[b], shuffled[a]
		})

		got, ok := Resolve(shuffled)
		if !ok {
			t.Fatalf("shuffle %d: Resolve = false", i)
		}
		if got != want {
			t.Fatalf("shuffle %d: Resolve = %+v, want %+v", i, got, want)
		}
	}
}
