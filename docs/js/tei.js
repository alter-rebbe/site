//import CETEI from './CETEI/CETEI.js'
import CETEI from './CETEI.js'

export default function loadTei(tei) {
    let wrapper = document
        .getElementsByClassName("page-content").item(0)
        .getElementsByClassName("wrapper").item(0);

    let teiDiv = document.createElement("div");
    teiDiv.innerHTML = "This page will only work in modern browsers.";
    wrapper.appendChild(teiDiv);

    let CETEIcean = new CETEI();

    let refBehavior    = [["[role][target]", ["<a target='$@role' href='$@target'>", "</a>"]]];
    CETEIcean.addBehaviors({
        "tei": {
            "pb"       : [["[role][target]", ["<a target='$@role' href='$@target'>⎙</a>"]]],
            "persName" : refBehavior,
            "placeName": refBehavior,
            "orgName"  : refBehavior,
            "supplied" : ["[", "]"]
        }
    });

    CETEIcean.getHTML5(tei, function (data) {
        teiDiv.innerHTML = "";
        teiDiv.appendChild(data);
    });
}
