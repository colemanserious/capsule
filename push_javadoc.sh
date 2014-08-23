#!/bin/sh
# Push javadoc files to a website hosted by github <http://pages.github.com/>.
# Before executing this script, generate the javadoc files into build/docs/javadoc/.
git checkout gh-pages || exit $?

# Clear out the old files:
rm -rf capsule/javadoc
rm -rf capsule-util/javadoc
rm -rf capsule-container/javadoc

# Replace them with new files and commit them:
cp -pr capsule/build/docs/javadoc/ capsule/javadoc
cp -pr capsule-util/build/docs/javadoc/ capsule-util/javadoc
cp -pr capsule-container/build/docs/javadoc/ capsule-container/javadoc

git add -A \
&& git commit -a -m "generated javadoc"

ERROR=$?

git checkout master || exit $?
[ $ERROR -eq 0 ] || exit $ERROR
git push github gh-pages || exit $?