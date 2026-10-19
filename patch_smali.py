#!/usr/bin/env python3
"""Add narrow service/UI hooks and isolate the clone's application contracts."""
from pathlib import Path
import argparse
import json
import re


def method_change(text, signature, change):
    start = text.index('.method ' + signature + '\n')
    end = text.index('.end method', start) + len('.end method')
    return text[:start] + change(text[start:end]) + text[end:]


def patch(decoded, metadata):
    decoded = Path(decoded)
    changes = []
    for root in decoded.glob('smali*'):
        for path in root.rglob('*.smali'):
            text = path.read_text()
            before = text

            def literal(match):
                value = match.group(1)
                mapped = metadata['permission_map'].get(value)
                if mapped is None:
                    mapped = value
                    if value == metadata['old_package']:
                        mapped = metadata['new_package']
                    elif metadata['old_package'] + '.playback.mediadb.provider' in value:
                        mapped = value.replace(metadata['old_package'] + '.playback.mediadb.provider', metadata['new_package'] + '.playback.mediadb.provider')
                    elif value == metadata['old_package'] + '.USB_PERMISSION':
                        mapped = metadata['new_package'] + '.USB_PERMISSION'
                    else:
                        for old, new in metadata['action_prefix_map'].items():
                            if value.startswith(old):
                                mapped = new + value[len(old):]
                                break
                if mapped != value:
                    changes.append({'file': str(path.relative_to(decoded)), 'from': value, 'to': mapped})
                return '"' + mapped + '"'

            text = re.sub(r'"([^"\n]*)"', literal, text)
            if path.name == 'PlaybackService.smali':
                if 'Lcom/rea/walkman/PlaybackExtras;' not in text:
                    def state(body):
                        needle = '    iput-object p1, p0, Lcom/sony/songpal/localplayer/playbackservice/PlaybackService;->mPlayerStatus:Lcom/sony/songpal/localplayer/playbackservice/Const$PlayState;'
                        assert body.count(needle) == 1
                        return body.replace(needle, needle + '\n\n    invoke-static {p0, p1}, Lcom/rea/walkman/PlaybackExtras;->onState(Landroid/content/Context;Ljava/lang/Enum;)V')
                    text = method_change(text, 'setPlayerStatus(Lcom/sony/songpal/localplayer/playbackservice/Const$PlayState;)V', state)

                    def completion(body):
                        needle = '    invoke-direct {p0}, Lcom/sony/songpal/localplayer/playbackservice/PlaybackService;->forceUpdateLastPlayedDate()V'
                        assert body.count(needle) == 1
                        hook = '''

    invoke-static {p0}, Lcom/rea/walkman/PlaybackExtras;->onTrackCompleted(Landroid/content/Context;)Z
    move-result v0
    if-eqz v0, :rea_continue_completion
    const/4 v0, 0x0
    invoke-virtual {p0, v0}, Lcom/sony/songpal/localplayer/playbackservice/PlaybackService;->pause(Lcom/sony/songpal/localplayer/playbackservice/PlaybackService$PauseListener;)V
    return-void
    :rea_continue_completion
'''
                        return body.replace(needle, needle + hook)
                    text = method_change(text, 'executeOnCompletion()V', completion)

                    def stop(body):
                        return body.replace('    .locals 2', '    .locals 2\n\n    invoke-static {p0}, Lcom/rea/walkman/PlaybackExtras;->onServiceStop(Landroid/content/Context;)V', 1)
                    text = method_change(text, 'public onDestroy()V', stop)
            elif path.name == 'PlaybackService$25.smali':
                if 'Lcom/rea/walkman/PlaybackExtras;' not in text:
                    def fetch(body):
                        needle = '    .locals 4'
                        hook = '''

    iget-object v0, p0, Lcom/sony/songpal/localplayer/playbackservice/PlaybackService$25;->this$0:Lcom/sony/songpal/localplayer/playbackservice/PlaybackService;
    invoke-static {v0}, Lcom/rea/walkman/PlaybackExtras;->timerActive(Landroid/content/Context;)Z
    move-result v0
    if-eqz v0, :rea_gapless_allowed
    const/4 v0, 0x0
    return-object v0
    :rea_gapless_allowed
'''
                        return body.replace(needle, needle + hook, 1)
                    text = method_change(text, 'public onFetchNext(I)Lcom/sony/songpal/localplayer/playbackservice/IPlayItemSequence;', fetch)

                    def moved(body):
                        # Native may already have fetched the next track before the timer was armed.
                        # Preserve the original metadata synchronization, then request normal pause.
                        needle = '    .line 4611\n    return-void'
                        hook = '''    iget-object v1, p0, Lcom/sony/songpal/localplayer/playbackservice/PlaybackService$25;->this$0:Lcom/sony/songpal/localplayer/playbackservice/PlaybackService;
    invoke-static {v1}, Lcom/rea/walkman/PlaybackExtras;->onTrackCompleted(Landroid/content/Context;)Z
    move-result v2
    if-eqz v2, :rea_gapless_continue
    const/4 v2, 0x0
    invoke-virtual {v1, v2}, Lcom/sony/songpal/localplayer/playbackservice/PlaybackService;->pause(Lcom/sony/songpal/localplayer/playbackservice/PlaybackService$PauseListener;)V
    :rea_gapless_continue
    return-void'''
                        assert body.count(needle) == 1
                        # The original catch-all covers the monitor region. Place the
                        # new call beyond that region so a failure never exits a released monitor.
                        return body.replace(needle, '    goto :rea_after_gapless_lock').replace(
                            '.end method', '\n    :rea_after_gapless_lock\n' + hook + '\n.end method')
                    text = method_change(text, 'private executeOnMoveToNext()V', moved)
            elif path.name == 'SettingFragment.smali':
                if 'Lcom/rea/walkman/PlaybackExtras;' not in text:
                    def settings(body):
                        needle = '    invoke-interface {v2, v3}, Ljava/util/List;->forEach(Ljava/util/function/Consumer;)V'
                        assert body.count(needle) == 1
                        return body.replace(needle, needle + '\n\n    invoke-static {v1}, Lcom/rea/walkman/PlaybackExtras;->appendSettings(Landroid/widget/LinearLayout;)V')
                    text = method_change(text, 'public onCreateView(Landroid/view/LayoutInflater;Landroid/view/ViewGroup;Landroid/os/Bundle;)Landroid/view/View;', settings)
            if text != before:
                path.write_text(text)
    return changes


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('decoded')
    parser.add_argument('metadata')
    parser.add_argument('--log', required=True)
    args = parser.parse_args()
    result = patch(args.decoded, json.loads(Path(args.metadata).read_text()))
    Path(args.log).write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print('Updated', len(result), 'application string occurrences and service/settings hooks')
