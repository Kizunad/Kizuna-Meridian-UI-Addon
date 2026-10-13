"""检查文档本地链接，避免独立仓库接入说明指向不存在的页面。"""

from pathlib import Path
import re
import sys


def main() -> int:
    # 文档源集中在 Wiki 目录，检查时排除依赖仓库和构建产物。
    root = Path(__file__).resolve().parents[1]
    pages = [root / "README.md", *sorted((root / "docs/wiki").glob("*.md"))]
    failures = []
    local_links = 0
    for page in pages:
        for target in re.findall(r"\[[^\]]+\]\(([^)]+)\)", page.read_text(encoding="utf-8")):
            if target.startswith(("https://", "http://", "#")):
                continue

            # 相对链接以页面目录为起点，锚点不参与文件存在性判断。
            relative_path = target.split("#", 1)[0]
            local_links += 1
            if not (page.parent / relative_path).is_file():
                failures.append(f"{page.relative_to(root)}: {target}")

    if failures:
        print("文档链接不存在：\n" + "\n".join(failures), file=sys.stderr)
        return 1

    print(f"检查通过：{len(pages)} 个页面，{local_links} 个本地链接。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
