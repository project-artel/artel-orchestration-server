// generated from wv-editor capture -- pseudo-C#, not compilable
// evidence-derived: bodies show only observed statements, in IL offset order
// capture=editor schema=6 unity=2022.3.62f3 platform=OSXEditor backend=mono sdk=0.1.0

using UnityEngine;
using UnityEngine.SceneManagement;
using System.Collections;

namespace Cards
{
    class BackButton : MonoBehaviour
    {
        [UnityEvent(wired: "Canvas/Button (Legacy)")]
        // confidence verified
        void BackToMain()
        {
            SceneManager.LoadScene("TitleScene");                      // IL_0005
            GameObject.Find("SaveLoadController").GetComponent().SavePlayData(); // IL_0019
        }
    }
}
