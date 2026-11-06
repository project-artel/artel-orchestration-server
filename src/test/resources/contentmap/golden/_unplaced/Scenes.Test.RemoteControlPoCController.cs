// generated from wv-editor capture -- pseudo-C#, not compilable
// evidence-derived: bodies show only observed statements, in IL offset order
// capture=editor schema=6 unity=2022.3.62f3 platform=OSXEditor backend=mono sdk=0.1.0
// UNPLACED: no scene object proven to host this type

using UnityEngine;
using UnityEngine.SceneManagement;
using System.Collections;

namespace Scenes.Test
{
    class RemoteControlPoCController : MonoBehaviour
    {
        [UnityLifecycle]
        // confidence verified
        void Awake()
        {
            RemoteControlPoCController.submitButton.onClick.AddListener(RemoteControlPoCController.CopyInputToOutput); // IL_0028
        }

        [UnityLifecycle]
        // reached from: RemoteControlPoCController.Awake
        // confidence partial; gaps: reached-through-delegate
        void CopyInputToOutput()
        {
            RemoteControlPoCController.outputText.text = RemoteControlPoCController.inputField.text; // IL_0011
            // control handed to UnityEngine.Events.UnityEvent::AddListener @ IL_0028
        }
    }
}
