package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

const (
	baseURL   = "https://api.github.com"
	userAgent = "workflow-cleanup/1.0"
	perPage   = 100
	maxPages  = 100 // safety cap: 10k runs per workflow
)

type Workflow struct {
	ID   int64  `json:"id"`
	Name string `json:"name"`
}

type workflowsResponse struct {
	Workflows []Workflow `json:"workflows"`
}

type Run struct {
	ID         int64     `json:"id"`
	CreatedAt  time.Time `json:"created_at"`
	Status     string    `json:"status"`
	Conclusion *string   `json:"conclusion"`
}

type runsResponse struct {
	WorkflowRuns []Run `json:"workflow_runs"`
}

type GitHubAPI struct {
	token  string
	repo   string
	client *http.Client
}

func NewGitHubAPI(token, repo string) *GitHubAPI {
	return &GitHubAPI{
		token:  token,
		repo:   repo,
		client: &http.Client{Timeout: 30 * time.Second},
	}
}

// doRequest performs a request with retries and rate-limit handling.
// Caller must close the returned response body.
func (g *GitHubAPI) doRequest(
	method, rawURL string,
	query url.Values,
) (*http.Response, error) {
	const maxRetries = 3
	const retryDelay = time.Second

	if len(query) > 0 {
		rawURL += "?" + query.Encode()
	}

	var lastErr error
	for attempt := 0; attempt < maxRetries; attempt++ {
		req, err := http.NewRequest(method, rawURL, nil)
		if err != nil {
			return nil, err
		}
		req.Header.Set("Authorization", "Bearer "+g.token)
		req.Header.Set("Accept", "application/vnd.github+json")
		req.Header.Set("X-GitHub-Api-Version", "2022-11-28")
		req.Header.Set("User-Agent", userAgent)

		resp, err := g.client.Do(req)
		if err != nil {
			lastErr = err
			time.Sleep(backoff(retryDelay, attempt))
			continue
		}

		// Rate limiting: 429, or 403 with exhausted rate limit
		if resp.StatusCode == http.StatusTooManyRequests ||
			(resp.StatusCode == http.StatusForbidden &&
				resp.Header.Get("X-RateLimit-Remaining") == "0") {
			wait := rateLimitWait(resp)
			drainAndClose(resp)
			fmt.Printf("Rate limit hit, waiting %s...\n", wait)
			time.Sleep(wait)
			lastErr = errors.New("rate limited")
			continue
		}

		// Permanent client errors: fail fast, no retry
		if resp.StatusCode == http.StatusUnauthorized ||
			resp.StatusCode == http.StatusForbidden ||
			resp.StatusCode == http.StatusNotFound {
			body := readBody(resp)
			return nil, fmt.Errorf(
				"API error %d: %s", resp.StatusCode, body,
			)
		}

		// Transient errors: retry
		if resp.StatusCode >= 400 {
			body := readBody(resp)
			lastErr = fmt.Errorf("status %d: %s", resp.StatusCode, body)
			time.Sleep(backoff(retryDelay, attempt))
			continue
		}

		return resp, nil
	}

	return nil, fmt.Errorf("request failed after retries: %w", lastErr)
}

func rateLimitWait(resp *http.Response) time.Duration {
	if ra := resp.Header.Get("Retry-After"); ra != "" {
		if s, err := strconv.Atoi(ra); err == nil && s > 0 {
			return time.Duration(s) * time.Second
		}
	}
	if reset, err := strconv.ParseInt(
		resp.Header.Get("X-RateLimit-Reset"), 10, 64,
	); err == nil && reset > 0 {
		wait := time.Until(time.Unix(reset, 0)) + time.Second
		if wait > time.Second {
			return wait
		}
	}
	return 5 * time.Second
}

func backoff(base time.Duration, attempt int) time.Duration {
	return time.Duration(float64(base) * math.Pow(2, float64(attempt)))
}

func readBody(resp *http.Response) string {
	defer resp.Body.Close()
	b, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
	return string(b)
}

func drainAndClose(resp *http.Response) {
	io.Copy(io.Discard, resp.Body)
	resp.Body.Close()
}

func (g *GitHubAPI) GetWorkflows() ([]Workflow, error) {
	u := fmt.Sprintf("%s/repos/%s/actions/workflows", baseURL, g.repo)
	resp, err := g.doRequest(http.MethodGet, u, nil)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	var data workflowsResponse
	if err := json.NewDecoder(resp.Body).Decode(&data); err != nil {
		return nil, fmt.Errorf("decode workflows: %w", err)
	}
	return data.Workflows, nil
}

func (g *GitHubAPI) GetWorkflowRuns(workflowID int64) ([]Run, error) {
	var all []Run
	u := fmt.Sprintf(
		"%s/repos/%s/actions/workflows/%d/runs", baseURL, g.repo, workflowID,
	)

	for page := 1; page <= maxPages; page++ {
		q := url.Values{}
		q.Set("per_page", strconv.Itoa(perPage))
		q.Set("page", strconv.Itoa(page))

		resp, err := g.doRequest(http.MethodGet, u, q)
		if err != nil {
			return all, fmt.Errorf("fetch runs page %d: %w", page, err)
		}

		var data runsResponse
		err = json.NewDecoder(resp.Body).Decode(&data)
		resp.Body.Close()
		if err != nil {
			return all, fmt.Errorf("decode runs page %d: %w", page, err)
		}

		if len(data.WorkflowRuns) == 0 {
			break
		}

		all = append(all, data.WorkflowRuns...)
		fmt.Printf("Fetched page %d (%d runs)\n", page, len(data.WorkflowRuns))

		if len(data.WorkflowRuns) < perPage {
			break
		}
	}

	return all, nil
}

func (g *GitHubAPI) DeleteWorkflowRun(runID int64) error {
	u := fmt.Sprintf("%s/repos/%s/actions/runs/%d", baseURL, g.repo, runID)
	resp, err := g.doRequest(http.MethodDelete, u, nil)
	if err != nil {
		return err
	}
	drainAndClose(resp)
	if resp.StatusCode != http.StatusNoContent {
		return fmt.Errorf("unexpected status %d", resp.StatusCode)
	}
	return nil
}

func parseWorkflowNames(s string) []string {
	var out []string
	for _, name := range strings.Split(s, ",") {
		if trimmed := strings.TrimSpace(name); trimmed != "" {
			out = append(out, trimmed)
		}
	}
	return out
}

func env(key, def string) string {
	if v, ok := os.LookupEnv(key); ok && strings.TrimSpace(v) != "" {
		return v
	}
	return def
}

func conclusionOf(r Run) string {
	if r.Conclusion == nil {
		return "None"
	}
	return *r.Conclusion
}

func main() {
	token := os.Getenv("GITHUB_TOKEN")
	repo := os.Getenv("REPO")

	if token == "" {
		fmt.Println("GITHUB_TOKEN environment variable is required")
		os.Exit(1)
	}
	if repo == "" {
		fmt.Println("REPO environment variable is required")
		os.Exit(1)
	}

	defaultWorkflows := "Build App,Copilot coding agent,Copilot Setup Steps," +
		"Dependabot Updates,Lock Threads,Recycle Runs,Release App," +
		"Update Go Version,Update Syncthing submodule"

	workflowNamesStr := env("WORKFLOW_NAMES", defaultWorkflows)

	daysToKeep := 14
	daysStr := strings.TrimSpace(env("DAYS_TO_KEEP", "14"))
	if v, err := strconv.Atoi(daysStr); err == nil && v >= 1 {
		daysToKeep = v
	} else if err != nil {
		fmt.Printf("Invalid DAYS_TO_KEEP '%s', using default: 14\n", daysStr)
	}

	dryRun := false
	switch strings.ToLower(strings.TrimSpace(env("DRY_RUN", "false"))) {
	case "true", "1", "yes", "on":
		dryRun = true
	}

	workflowNames := parseWorkflowNames(workflowNamesStr)
	if len(workflowNames) == 0 {
		fmt.Println("No valid workflow names provided")
		os.Exit(1)
	}

	fmt.Printf("Starting workflow runs cleanup for repository: %s\n", repo)
	fmt.Printf("Target workflows: %s\n", workflowNamesStr)
	fmt.Printf("Keeping runs newer than %d days\n", daysToKeep)
	fmt.Printf("Dry run mode: %v\n\n", dryRun)

	api := NewGitHubAPI(token, repo)
	cutoff := time.Now().UTC().AddDate(0, 0, -daysToKeep)

	fmt.Println("Fetching workflows...")
	workflows, err := api.GetWorkflows()
	if err != nil {
		fmt.Printf("Failed to fetch workflows: %v\n", err)
		os.Exit(1)
	}
	if len(workflows) == 0 {
		fmt.Println("No workflows found")
		os.Exit(1)
	}

	byName := make(map[string]Workflow, len(workflows))
	for _, w := range workflows {
		byName[w.Name] = w
	}

	var targets []Workflow
	for _, name := range workflowNames {
		if w, ok := byName[name]; ok {
			targets = append(targets, w)
			fmt.Printf("Found workflow '%s' (ID: %d)\n", name, w.ID)
		} else {
			fmt.Printf("Workflow '%s' not found\n", name)
		}
	}

	if len(targets) == 0 {
		fmt.Println("No valid workflows found to process")
		os.Exit(1)
	}

	fmt.Println()

	totalDeleted, totalFailed, totalSkipped, totalWouldDelete := 0, 0, 0, 0

	for _, w := range targets {
		fmt.Printf("Processing workflow '%s' (ID: %d)\n", w.Name, w.ID)

		runs, err := api.GetWorkflowRuns(w.ID)
		if err != nil {
			fmt.Printf("Warning: partial fetch for '%s': %v\n", w.Name, err)
		}
		if len(runs) == 0 {
			fmt.Printf("No runs found for workflow '%s'\n\n", w.Name)
			continue
		}

		fmt.Printf("Found %d total runs\n", len(runs))

		var oldRuns []Run
		for _, r := range runs {
			// Skip in-progress runs; GitHub rejects deleting them
			if r.CreatedAt.Before(cutoff) && r.Status == "completed" {
				oldRuns = append(oldRuns, r)
			}
		}

		totalSkipped += len(runs) - len(oldRuns)

		if len(oldRuns) == 0 {
			fmt.Printf(
				"No deletable runs older than %d days for '%s'\n\n",
				daysToKeep, w.Name,
			)
			continue
		}

		fmt.Printf(
			"Found %d runs older than %d days\n", len(oldRuns), daysToKeep,
		)

		deleted := 0
		for _, r := range oldRuns {
			created := r.CreatedAt.UTC().Format(time.RFC3339)
			if dryRun {
				fmt.Printf(
					"Would delete Run-ID %d (created: %s, conclusion: %s)\n",
					r.ID, created, conclusionOf(r),
				)
				continue
			}

			if err := api.DeleteWorkflowRun(r.ID); err != nil {
				fmt.Printf("Failed to delete Run-ID %d: %v\n", r.ID, err)
				totalFailed++
			} else {
				fmt.Printf("Deleted Run-ID %d (created: %s)\n", r.ID, created)
				deleted++
			}
			time.Sleep(200 * time.Millisecond)
		}

		if dryRun {
			totalWouldDelete += len(oldRuns)
		} else {
			fmt.Printf(
				"Completed '%s': %d/%d runs deleted\n",
				w.Name, deleted, len(oldRuns),
			)
			totalDeleted += deleted
		}
		fmt.Println()
	}

	fmt.Println("Summary:")
	if dryRun {
		fmt.Printf("Dry run: %d runs would be deleted\n", totalWouldDelete)
	} else {
		fmt.Printf("Total runs deleted: %d\n", totalDeleted)
		fmt.Printf("Total runs failed: %d\n", totalFailed)
	}
	fmt.Printf(
		"Total runs skipped (newer than %d days or in progress): %d\n",
		daysToKeep, totalSkipped,
	)
	fmt.Printf("Processed %d workflow(s)\n", len(targets))

	if totalFailed > 0 {
		os.Exit(1)
	}
	fmt.Println("\nWorkflow runs cleanup completed successfully!")
}