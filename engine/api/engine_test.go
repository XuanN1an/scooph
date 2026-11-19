package api

import (
	"encoding/json"
	"path/filepath"
	"testing"

	"github.com/scooph/scooph/engine/manifest"
	"github.com/scooph/scooph/engine/version"
)

func TestPlanReturnsNewerRelation(t *testing.T) {
	e := NewEngine(filepath.Join(t.TempDir(), "engine"))

	req := struct {
		Candidates []version.Candidate `json:"candidates"`
		Installed  []struct {
			Package     string `json:"package"`
			VersionCode int64  `json:"versionCode"`
		} `json:"installed"`
	}{
		Candidates: []version.Candidate{
			{
				Entry: manifest.Entry{
					Package:     "com.a",
					Name:        "App A",
					VersionCode: 7000,
					APK:         manifest.APK{URL: "https://x/a7000.apk", SHA256: "aaa", Size: 100},
				},
				SourceID:    "source-a",
				SourceOrder: 2,
			},
			{
				Entry: manifest.Entry{
					Package:     "com.a",
					Name:        "App A old",
					VersionCode: 6900,
					APK:         manifest.APK{URL: "https://x/a6900.apk", SHA256: "bbb", Size: 90},
				},
				SourceID:    "source-b",
				SourceOrder: 1,
			},
		},
		Installed: []struct {
			Package     string `json:"package"`
			VersionCode int64  `json:"versionCode"`
		}{
			{Package: "com.a", VersionCode: 6804},
		},
	}
	b, _ := json.Marshal(req)
	out, err := e.Plan(string(b))
	if err != nil {
		t.Fatalf("Plan error: %v", err)
	}

	var parsed struct {
		Items []struct {
			Package              string `json:"package"`
			Relation             string `json:"relation"`
			EntryVersionCode     int64  `json:"entryVersionCode"`
			InstalledVersionCode int64  `json:"installedVersionCode"`
			SourceID             string `json:"sourceId"`
		} `json:"items"`
	}
	if err := json.Unmarshal([]byte(out), &parsed); err != nil {
		t.Fatalf("decode plan: %v\nout=%s", err, out)
	}
	if len(parsed.Items) != 1 {
		t.Fatalf("items=%d, want 1 (%s)", len(parsed.Items), out)
	}
	got := parsed.Items[0]
	if got.Package != "com.a" || got.Relation != "newer" || got.EntryVersionCode != 7000 || got.InstalledVersionCode != 6804 || got.SourceID != "source-a" {
		t.Fatalf("unexpected item: %+v", got)
	}
}

func TestPlanEmptyCandidatesReturnsEmptyArray(t *testing.T) {
	e := NewEngine(filepath.Join(t.TempDir(), "engine"))
	out, err := e.Plan(`{"candidates":[],"installed":[{"package":"com.a","versionCode":6804}]}`)
	if err != nil {
		t.Fatalf("Plan error: %v", err)
	}

	var parsed struct {
		Items []json.RawMessage `json:"items"`
	}
	if err := json.Unmarshal([]byte(out), &parsed); err != nil {
		t.Fatalf("decode plan: %v", err)
	}
	if parsed.Items == nil {
		t.Fatalf("items is nil, want [] (out=%s)", out)
	}
	if len(parsed.Items) != 0 {
		t.Fatalf("items=%d, want 0 (out=%s)", len(parsed.Items), out)
	}
}

func TestPlanAcceptsLowerCaseKotlinStyleKeys(t *testing.T) {
	e := NewEngine(filepath.Join(t.TempDir(), "engine"))
	req := `{
	  "candidates": [
	    {
	      "entry": {
	        "package": "com.a",
	        "name": "App A",
	        "versionCode": 7000,
	        "apk": {"url": "https://x/a.apk", "size": 1, "sha256": "abc"}
	      },
	      "sourceId": "bucket-a",
	      "sourceOrder": 0
	    }
	  ],
	  "installed": [
	    {"package": "com.a", "versionCode": 6804}
	  ]
	}`
	out, err := e.Plan(req)
	if err != nil {
		t.Fatalf("Plan error: %v", err)
	}
	var parsed struct {
		Items []struct {
			SourceID string `json:"sourceId"`
			Relation string `json:"relation"`
		} `json:"items"`
	}
	if err := json.Unmarshal([]byte(out), &parsed); err != nil {
		t.Fatalf("decode plan: %v", err)
	}
	if len(parsed.Items) != 1 {
		t.Fatalf("items=%d, want 1 (out=%s)", len(parsed.Items), out)
	}
	if parsed.Items[0].SourceID != "bucket-a" || parsed.Items[0].Relation != "newer" {
		t.Fatalf("unexpected item: %+v", parsed.Items[0])
	}
}
