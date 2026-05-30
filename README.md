# CodeCrafters Build Your Own X

Parent workspace for learning systems projects from scratch.

This repository is the index repo. Each challenge lives in its own child Git
repository so it can still be submitted to CodeCrafters exactly the way
CodeCrafters expects.

## Projects

| Project | Local path | CodeCrafters challenge |
| --- | --- | --- |
| Build Your Own Redis | `codecrafters-redis-java` | Redis, Java |

## Repository Model

- The parent repo is pushed to GitHub:
  `https://github.com/Bahubuli/Codecrafters-Build-from-scratch.git`
- `codecrafters-redis-java` is a Git submodule. It keeps its own commits and
  remotes.
- In this Codespace, inside `codecrafters-redis-java`, `origin` points to
  CodeCrafters and `github` points to the GitHub mirror:
  `https://github.com/Bahubuli/codecrafters-redis-java.git`.
- In a fresh clone of the parent repo, the Redis submodule will clone from
  GitHub. Add the CodeCrafters remote from the CodeCrafters setup page before
  submitting:

```sh
cd codecrafters-redis-java
git remote add codecrafters <codecrafters-git-url>
```

CodeCrafters creates a Git repository per challenge and runs tests when code is
submitted. You do not need a separate branch for CodeCrafters. Keep one branch,
`master`, and push that same branch to different remotes:

- CodeCrafters remote: runs challenge tests.
- GitHub remote: lets you view and back up the code.

Their official GitHub publishing flow is one-way: push to CodeCrafters first,
then CodeCrafters can sync `master` to GitHub. If you use a manual GitHub mirror
instead, push the child repo to GitHub after the CodeCrafters push.

## Daily Workflow

Work inside the child challenge:

```sh
cd codecrafters-redis-java
# edit code
git status
git add .
git commit -m "Implement next Redis stage"
git push origin master   # CodeCrafters in this Codespace
git push github master   # Manual GitHub mirror
```

Then update the parent repo so GitHub records which child commit it points to:

```sh
cd ..
git add codecrafters-redis-java
git commit -m "Update Redis project pointer"
git push origin master
```

You can also run:

```sh
./scripts/status-all.sh
./scripts/push-redis.sh
```

## Adding Another CodeCrafters Project

Clone the CodeCrafters repo beside the Redis project:

```sh
git submodule add <github-mirror-url> codecrafters-kafka-java
cd codecrafters-kafka-java
git remote add codecrafters <codecrafters-git-url>
```

For new projects, prefer this remote naming inside the child:

- `origin`: the place you cloned from.
- `codecrafters`: CodeCrafters, if `origin` is not CodeCrafters.
- `github`: your GitHub mirror, if you are maintaining one manually.

If you use CodeCrafters' "Publish to GitHub" button, keep pushing to the
CodeCrafters remote. Do not push directly to that generated GitHub mirror unless
you intentionally want a separate manual mirror workflow.
