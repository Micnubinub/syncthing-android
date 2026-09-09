package utils

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"runtime"
	"strings"
	"time"
)

// Fail prints a formatted message to stderr and exits with code 1.
func Fail(format string, args ...any) {
	fmt.Fprintf(os.Stderr, format+"\n", args...)
	os.Exit(1)
}

// Which resolves a program on PATH, trying Windows extensions first.
func Which(program string) string {
	if runtime.GOOS == "windows" {
		for _, ext := range []string{".bat", ".cmd", ".exe"} {
			if p, err := exec.LookPath(program + ext); err == nil {
				return p
			}
		}
	}
	p, err := exec.LookPath(program)
	if err != nil {
		return ""
	}
	return p
}

// RunGit runs git with the given arguments, returning its trimmed stdout.
// When quiet is false the output is also echoed to the terminal.
func RunGit(args []string, quiet bool) string {
	cmd := exec.Command("git", args...)
	if !quiet {
		cmd.Stderr = os.Stderr
	}
	out, err := cmd.Output()
	if err != nil {
		Fail("git %s: %v", strings.Join(args, " "), err)
	}
	result := strings.TrimSpace(string(out))
	if !quiet && result != "" {
		fmt.Println(result)
	}
	return result
}

// Ask returns value trimmed, or prompts the user on stdin when value is empty.
func Ask(value, label string) string {
	if value != "" {
		return strings.TrimSpace(value)
	}
	fmt.Print(label)
	reader := bufio.NewReader(os.Stdin)
	line, _ := reader.ReadString('\n')
	return strings.TrimSpace(line)
}

// DownloadFile downloads url to dest, writing to a .partial file first so an
// interrupted download never corrupts an existing file at dest.
func DownloadFile(url, dest string) error {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()

	client := &http.Client{
		Transport: &http.Transport{
			ResponseHeaderTimeout: 2 * time.Minute,
		},
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return err
	}
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("unexpected status %s for %s", resp.Status, url)
	}

	tmp := dest + ".partial"
	f, err := os.Create(tmp)
	if err != nil {
		return err
	}
	if _, err = io.Copy(f, resp.Body); err != nil {
		f.Close()
		os.Remove(tmp)
		return err
	}
	if err := f.Close(); err != nil {
		os.Remove(tmp)
		return err
	}
	if err := os.Rename(tmp, dest); err != nil {
		os.Remove(tmp)
		return err
	}
	return nil
}

// Run runs name with args in dir, streaming stdout, stderr and stdin through.
func Run(dir string, name string, args ...string) error {
	cmd := exec.Command(name, args...)
	cmd.Dir = dir
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	cmd.Stdin = os.Stdin
	return cmd.Run()
}
