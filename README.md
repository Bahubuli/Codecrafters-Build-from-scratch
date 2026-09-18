# CodeCrafters Build Your Own X

Parent workspace for learning systems projects from scratch.

This repository is the main learning repo. Each challenge lives in its own
folder so the code is visible directly on GitHub.

## Projects

| Project | Local path | CodeCrafters challenge |
| --- | --- | --- |
| Build Your Own Redis | `codecrafters-redis-java` | Redis, Java |
| Build Your Own Shell | `codecrafters-shell-java` | Shell, Java |

## Repository Model

- The parent repo is pushed to GitHub:
  `https://github.com/Bahubuli/Codecrafters-Build-from-scratch.git`
- Each challenge (`codecrafters-redis-java`, `codecrafters-shell-java`) is a normal folder tracked by the parent repo.
- The parent repo has extra remotes:
  - `redis-codecrafters` pointing to the CodeCrafters Redis repository.
  - `shell-codecrafters` pointing to the CodeCrafters Shell repository.
- You do not need a separate local branch for CodeCrafters. Keep one branch,
  `master`, and submit challenge folders to CodeCrafters with the helper scripts.

```sh
git remote -v
```

CodeCrafters creates a Git repository per challenge and runs tests when code is
submitted. In this repo, GitHub receives the whole learning workspace, while
CodeCrafters receives only the challenge folder.

## Daily Workflow

Work inside the challenge folder:

```sh
cd codecrafters-shell-java
# edit code
cd ..
git status
git add .
git commit -m "Solve Stage NN: <Title>"
git push origin master
```

Then submit the challenge folder to CodeCrafters:

```sh
powershell -File scripts/push-shell.ps1 "Solve Stage NN: <Title>"
```
