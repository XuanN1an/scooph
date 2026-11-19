// Package api 是 gomobile 的暴露面：只有这一个包会被编进 AAR。
package api

// Engine 是 Go 引擎的句柄。root 是引擎自己的工作目录，
// 后续任务在这里放仓库缓存、下载文件等。
type Engine struct {
	root string
}

// NewEngine 构造引擎并把工作目录固定下来。
func NewEngine(root string) *Engine {
	return &Engine{root: root}
}

// Version 返回引擎版本。M1 只需要一个非空值来验证跨语言链路。
func (e *Engine) Version() string {
	return "0.1.0"
}
