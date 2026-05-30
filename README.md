# CodeCrafters Build Your Own X

Parent workspace for learning systems projects from scratch.

This repository is the main learning repo. Each challenge lives in its own
folder so the code is visible directly on GitHub.

## Projects

| Project | Local path | CodeCrafters challenge |
| --- | --- | --- |
| Build Your Own Redis | `codecrafters-redis-java` | Redis, Java |

## Repository Model

- The parent repo is pushed to GitHub:
  `https://github.com/Bahubuli/Codecrafters-Build-from-scratch.git`
- `codecrafters-redis-java` is a normal folder tracked by the parent repo.
- The parent repo has an extra remote named `redis-codecrafters` that points to
  the CodeCrafters Redis repository.
- You do not need a separate local branch for CodeCrafters. Keep one branch,
  `master`, and submit the Redis folder to the CodeCrafters remote with the
  helper script.

```sh
git remote -v
```

CodeCrafters creates a Git repository per challenge and runs tests when code is
submitted. In this repo, GitHub receives the whole learning workspace, while
CodeCrafters receives only the challenge folder.

Their official GitHub publishing flow is one-way: push to CodeCrafters first,
then CodeCrafters can sync `master` to GitHub. Since this repo already keeps the
code visible in the parent GitHub repo, you do not need CodeCrafters' GitHub
mirror feature.

## Daily Workflow

Work inside the challenge folder:

```sh
cd codecrafters-redis-java
# edit code
cd ..
git status
git add .
git commit -m "Implement next Redis stage"
git push origin master
```

Then submit the Redis folder to CodeCrafters:

```sh
./scripts/push-redis.sh "Implement next Redis stage"
```

You can also run:

```sh
./scripts/status-all.sh
./scripts/push-redis.sh
```

## Adding Another CodeCrafters Project

Add a normal folder for the new challenge:

```sh
mkdir codecrafters-kafka-java
git remote add kafka-codecrafters <codecrafters-git-url>
```

For new projects, prefer this remote naming in the parent repo:

- `origin`: the parent GitHub repo.
- `<project>-codecrafters`: the CodeCrafters remote for that challenge.

Copy `scripts/push-redis.sh` for the new project and change the project folder
and remote names.
